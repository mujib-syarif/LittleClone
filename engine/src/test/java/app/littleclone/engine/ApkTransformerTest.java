package app.littleclone.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ApkTransformerTest {
    private static final String OLD = "com.example.game";
    private static final String NEW = "com.example.game.lc2";

    private final Random random = new Random(42);

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    /** Smallest valid resources.arsc: one package chunk named {@code pkg}. */
    private static byte[] arsc(String pkg) {
        byte[] emptyPool = new StringPool(true).toChunk();
        int pkgSize = 288 + emptyPool.length * 2;
        int total = 12 + emptyPool.length + pkgSize;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0002).putShort((short) 12).putInt(total).putInt(1);
        b.put(emptyPool);
        b.putShort((short) 0x0200).putShort((short) 288).putInt(pkgSize).putInt(0x7f);
        byte[] name = pkg.getBytes(StandardCharsets.UTF_16LE);
        b.put(name);
        b.position(b.position() + (256 - name.length));
        b.putInt(288).putInt(0).putInt(288 + emptyPool.length).putInt(0).putInt(0);
        b.put(emptyPool).put(emptyPool);
        return b.array();
    }

    private File buildApk(File dir) throws IOException {
        File f = new File(dir, "in.apk");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        try {
            put(z, "AndroidManifest.xml", ManifestPatcherTest.resource("manifest_utf8.xml"), false);
            put(z, "classes.dex", new byte[40000], false);
            put(z, "resources.arsc", arsc(OLD), true);
            put(z, "lib/arm64-v8a/libgame.so", bytes(70001), true);
            put(z, "lib/armeabi-v7a/libgame.so", bytes(60003), true);
            put(z, "lib/x86/libgame.so", bytes(50007), false);
            put(z, "META-INF/CERT.RSA", bytes(100), false);
            put(z, "META-INF/CERT.SF", bytes(100), false);
            put(z, "META-INF/MANIFEST.MF", bytes(100), false);
            put(z, "META-INF/services/foo.Bar", "impl.Class\n".getBytes(StandardCharsets.UTF_8), false);
            put(z, "res/raw/odd.bin", bytes(1237), true);
        } finally {
            z.close();
        }
        return f;
    }

    private static void put(ZipOutputStream z, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    private static byte[] read(ZipFile z, String name) throws IOException {
        InputStream in = z.getInputStream(z.getEntry(name));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static File tempDir() throws IOException {
        File d = File.createTempFile("lc-test", "");
        assertTrue(d.delete());
        assertTrue(d.mkdirs());
        d.deleteOnExit();
        return d;
    }

    @Test
    public void transformsApk() throws IOException {
        File dir = tempDir();
        File in = buildApk(dir);
        File out = new File(dir, "out.apk");
        CloneSpec spec = new CloneSpec(OLD, NEW, "lc2", "Game 2",
                Arrays.asList("arm64-v8a", "armeabi-v7a"));

        ApkTransformer.Result r = ApkTransformer.transform(in, out, spec, null);
        assertEquals("arm64-v8a", r.keptAbi);
        assertTrue(r.resourceTablePatched);
        assertEquals(5, r.entriesDropped); // 3 signature files + 2 foreign-ABI libraries

        ZipFile zin = new ZipFile(in);
        ZipFile zout = new ZipFile(out);
        try {
            List<String> names = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zout.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }
            assertTrue(names.contains("META-INF/services/foo.Bar"));
            assertTrue(names.contains("lib/arm64-v8a/libgame.so"));
            assertFalse(names.contains("lib/armeabi-v7a/libgame.so"));
            assertFalse(names.contains("lib/x86/libgame.so"));
            assertFalse(names.contains("META-INF/CERT.RSA"));
            assertFalse(names.contains("META-INF/MANIFEST.MF"));

            assertArrayEquals(read(zin, "classes.dex"), read(zout, "classes.dex"));
            assertArrayEquals(read(zin, "lib/arm64-v8a/libgame.so"), read(zout, "lib/arm64-v8a/libgame.so"));
            assertArrayEquals(read(zin, "res/raw/odd.bin"), read(zout, "res/raw/odd.bin"));
            assertEquals(NEW, ManifestPatcher.readPackage(read(zout, "AndroidManifest.xml")));

            byte[] table = read(zout, "resources.arsc");
            String name = new String(table, 12 + 28 + 12, 256, StandardCharsets.UTF_16LE).trim();
            assertEquals(NEW, name.replace("\u0000", ""));
        } finally {
            zin.close();
            zout.close();
        }
        assertStoredEntriesAligned(out);
    }

    /** Stored entries must start on 4-byte boundaries (16 KiB for native libraries). */
    private static void assertStoredEntriesAligned(File apk) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(apk, "r");
        try {
            List<ZipIndex.Entry> entries = ZipIndex.read(raf);
            assertFalse(entries.isEmpty());
            for (ZipIndex.Entry e : entries) {
                long offset = ZipIndex.dataOffset(raf, e);
                if (e.method == 0) {
                    long need = e.name.endsWith(".so") ? 16384 : 4;
                    assertEquals(e.name + " misaligned", 0, offset % need);
                }
            }
        } finally {
            raf.close();
        }
    }

    @Test
    public void keepsEveryAbiWhenNoneIsPreferred() throws IOException {
        File dir = tempDir();
        File in = buildApk(dir);
        File out = new File(dir, "out.apk");
        CloneSpec spec = new CloneSpec(OLD, NEW, "lc2", null, Arrays.asList("mips"));
        ApkTransformer.Result r = ApkTransformer.transform(in, out, spec, null);
        assertNull(r.keptAbi);
        assertEquals(3, r.entriesDropped); // only the signature files
    }

    @Test
    public void recognisesV1SignatureFilesOnly() {
        assertTrue(ApkTransformer.isSignatureFile("META-INF/CERT.RSA"));
        assertTrue(ApkTransformer.isSignatureFile("META-INF/MANIFEST.MF"));
        assertTrue(ApkTransformer.isSignatureFile("META-INF/ANDROIDD.SF"));
        assertFalse(ApkTransformer.isSignatureFile("META-INF/services/x.SF"));
        assertFalse(ApkTransformer.isSignatureFile("META-INF/kotlin_module"));
        assertFalse(ApkTransformer.isSignatureFile("assets/key.RSA"));
        assertNotNull(NEW);
    }
}
