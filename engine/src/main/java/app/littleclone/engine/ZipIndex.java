package app.littleclone.engine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ZIP central-directory reader. It never decompresses anything; it only tells us
 * where each entry's raw bytes live so they can be copied untouched (no re-deflate, which is
 * what keeps cloning a 200 MB game fast on an eMMC phone).
 */
final class ZipIndex {

    static final class Entry {
        final String name;
        final int flags;
        final int method;
        final int dosTime;
        final int dosDate;
        final long crc;
        final long compressedSize;
        final long size;
        final long localHeaderOffset;

        Entry(String name, int flags, int method, int dosTime, int dosDate,
              long crc, long compressedSize, long size, long localHeaderOffset) {
            this.name = name;
            this.flags = flags;
            this.method = method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
            this.localHeaderOffset = localHeaderOffset;
        }

        boolean isDirectory() {
            return name.endsWith("/");
        }
    }

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int LFH_SIG = 0x04034b50;

    private ZipIndex() {
    }

    static List<Entry> read(RandomAccessFile f) throws IOException {
        long len = f.length();
        if (len < 22) {
            throw new MalformedApkException("File is too small to be an APK");
        }
        int tailLen = (int) Math.min(len, 22 + 65535);
        byte[] tail = new byte[tailLen];
        f.seek(len - tailLen);
        f.readFully(tail);
        ByteBuffer tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);

        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (tb.getInt(i) == EOCD_SIG) {
                int commentLen = tb.getShort(i + 20) & 0xFFFF;
                if (i + 22 + commentLen == tailLen) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0) {
            throw new MalformedApkException("ZIP end-of-central-directory record not found");
        }
        int entryCount = tb.getShort(eocd + 10) & 0xFFFF;
        long cdSize = tb.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = tb.getInt(eocd + 16) & 0xFFFFFFFFL;
        if (entryCount == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new MalformedApkException("ZIP64 archives are not supported");
        }
        if (cdOffset + cdSize > len || cdSize > Integer.MAX_VALUE) {
            throw new MalformedApkException("Corrupt ZIP central directory");
        }

        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOffset);
        f.readFully(cd);
        ByteBuffer b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);

        List<Entry> out = new ArrayList<Entry>(entryCount);
        int p = 0;
        for (int i = 0; i < entryCount; i++) {
            if (p + 46 > cd.length || b.getInt(p) != CD_SIG) {
                throw new MalformedApkException("Corrupt ZIP central directory entry");
            }
            int flags = b.getShort(p + 8) & 0xFFFF;
            int method = b.getShort(p + 10) & 0xFFFF;
            int time = b.getShort(p + 12) & 0xFFFF;
            int date = b.getShort(p + 14) & 0xFFFF;
            long crc = b.getInt(p + 16) & 0xFFFFFFFFL;
            long csize = b.getInt(p + 20) & 0xFFFFFFFFL;
            long size = b.getInt(p + 24) & 0xFFFFFFFFL;
            int nameLen = b.getShort(p + 28) & 0xFFFF;
            int extraLen = b.getShort(p + 30) & 0xFFFF;
            int commentLen = b.getShort(p + 32) & 0xFFFF;
            long localOff = b.getInt(p + 42) & 0xFFFFFFFFL;
            if (p + 46 + nameLen + extraLen + commentLen > cd.length) {
                throw new MalformedApkException("Corrupt ZIP central directory entry");
            }
            if ((flags & 1) != 0) {
                throw new MalformedApkException("Encrypted ZIP entries are not supported");
            }
            if (method != 0 && method != 8) {
                throw new MalformedApkException("Unsupported ZIP compression method " + method);
            }
            String name = new String(cd, p + 46, nameLen, StandardCharsets.UTF_8);
            out.add(new Entry(name, flags, method, time, date, crc, csize, size, localOff));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    /** Returns the absolute file offset where this entry's (possibly compressed) data begins. */
    static long dataOffset(RandomAccessFile f, Entry e) throws IOException {
        byte[] h = new byte[30];
        f.seek(e.localHeaderOffset);
        f.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != LFH_SIG) {
            throw new MalformedApkException("Corrupt ZIP local header for " + e.name);
        }
        int nameLen = b.getShort(26) & 0xFFFF;
        int extraLen = b.getShort(28) & 0xFFFF;
        return e.localHeaderOffset + 30 + nameLen + extraLen;
    }
}
