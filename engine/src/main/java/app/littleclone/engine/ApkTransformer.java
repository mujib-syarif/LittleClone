package app.littleclone.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Turns one APK (a base APK or a split) into an unsigned clone APK.
 *
 * <p>Per APK it: patches AndroidManifest.xml and resources.arsc, drops the old signature files,
 * strips native libraries for ABIs the device would not use, and copies every other entry as raw
 * bytes (no recompression). Stored entries are re-aligned the way Android expects. The result
 * still has to be signed (the app does that with apksig) before it can be installed.
 */
public final class ApkTransformer {

    /** Receives progress in bytes of input processed so far. */
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    public static final class Result {
        /** ABI whose libraries were kept, or null if the APK has no native libraries. */
        public final String keptAbi;
        public final int entriesWritten;
        public final int entriesDropped;
        public final boolean resourceTablePatched;

        Result(String keptAbi, int written, int dropped, boolean arscPatched) {
            this.keptAbi = keptAbi;
            this.entriesWritten = written;
            this.entriesDropped = dropped;
            this.resourceTablePatched = arscPatched;
        }
    }

    private static final String MANIFEST = "AndroidManifest.xml";
    private static final String ARSC = "resources.arsc";
    private static final int ALIGN_DEFAULT = 4;
    private static final int ALIGN_NATIVE_LIB = 16384; // multiple of the 4 KiB page size in use today
    private static final long MAX_PATCHED_ENTRY = 256L * 1024 * 1024;

    private ApkTransformer() {
    }

    public static Result transform(File input, File output, CloneSpec spec, ProgressListener progress)
            throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(input, "r");
             FileOutputStream fos = new FileOutputStream(output)) {
            FileChannel in = raf.getChannel();
            FileChannel out = fos.getChannel();

            List<ZipIndex.Entry> entries = ZipIndex.read(raf);
            String abi = chooseAbi(entries, spec);

            long total = 0;
            for (ZipIndex.Entry e : entries) {
                total += e.compressedSize;
            }
            long done = 0;
            int written = 0;
            int dropped = 0;
            boolean manifestSeen = false;
            boolean arscPatched = false;

            ZipOut zip = new ZipOut(out);
            for (ZipIndex.Entry e : entries) {
                done += e.compressedSize;
                if (isSignatureFile(e.name) || isUnwantedAbi(e.name, abi)) {
                    dropped++;
                    continue;
                }
                if (e.name.equals(MANIFEST)) {
                    byte[] patched = ManifestPatcher.patch(readEntry(raf, in, e), spec);
                    zip.addStored(MANIFEST, patched, e.dosTime, e.dosDate, ALIGN_DEFAULT);
                    manifestSeen = true;
                } else if (e.name.equals(ARSC)) {
                    byte[] table = readEntry(raf, in, e);
                    arscPatched = ArscPatcher.renamePackage(table, spec.oldPackage, spec.newPackage);
                    zip.addStored(ARSC, table, e.dosTime, e.dosDate, ALIGN_DEFAULT);
                } else {
                    int align = e.name.endsWith(".so") ? ALIGN_NATIVE_LIB : ALIGN_DEFAULT;
                    zip.addRaw(e, in, ZipIndex.dataOffset(raf, e), align);
                }
                written++;
                if (progress != null) {
                    progress.onProgress(done, total);
                }
            }
            if (!manifestSeen) {
                throw new MalformedApkException("APK has no AndroidManifest.xml");
            }
            zip.finish();
            out.force(false);
            return new Result(abi, written, dropped, arscPatched);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Mirrors the platform: the first preferred ABI that the package ships libraries for. */
    static String chooseAbi(List<ZipIndex.Entry> entries, CloneSpec spec) {
        Set<String> present = new LinkedHashSet<String>();
        for (ZipIndex.Entry e : entries) {
            String abi = abiOf(e.name);
            if (abi != null) {
                present.add(abi);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        for (String preferred : spec.abiOrder) {
            if (present.contains(preferred)) {
                return preferred;
            }
        }
        return null; // unknown ABI set: keep everything rather than guess
    }

    private static String abiOf(String name) {
        if (!name.startsWith("lib/")) {
            return null;
        }
        int slash = name.indexOf('/', 4);
        return slash > 4 ? name.substring(4, slash) : null;
    }

    private static boolean isUnwantedAbi(String name, String keep) {
        if (keep == null) {
            return false;
        }
        String abi = abiOf(name);
        return abi != null && !abi.equals(keep);
    }

    /** Top-level META-INF signature files (v1 signing); nested META-INF entries are real content. */
    static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', 9) >= 0) {
            return false;
        }
        String n = name.toUpperCase(java.util.Locale.ROOT);
        return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    private static byte[] readEntry(RandomAccessFile raf, FileChannel in, ZipIndex.Entry e) throws IOException {
        if (e.size > MAX_PATCHED_ENTRY || e.compressedSize > MAX_PATCHED_ENTRY) {
            throw new MalformedApkException(e.name + " is unexpectedly large");
        }
        long offset = ZipIndex.dataOffset(raf, e);
        ByteBuffer raw = ByteBuffer.allocate((int) e.compressedSize);
        while (raw.hasRemaining()) {
            int n = in.read(raw, offset + raw.position());
            if (n < 0) {
                throw new IOException("Unexpected end of file while reading " + e.name);
            }
        }
        if (e.method == 0) {
            return raw.array();
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(raw.array());
            byte[] result = new byte[(int) e.size];
            int off = 0;
            while (off < result.length) {
                int n = inflater.inflate(result, off, result.length - off);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                off += n;
            }
            if (off != result.length) {
                throw new MalformedApkException("Could not fully inflate " + e.name);
            }
            return result;
        } catch (DataFormatException ex) {
            throw new MalformedApkException("Corrupt compressed data in " + e.name, ex);
        } finally {
            inflater.end();
        }
    }
}
