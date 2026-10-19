package app.littleclone.engine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Streaming ZIP writer that can (a) copy already-compressed entries byte-for-byte and
 * (b) pad stored entries to an alignment boundary, which Android requires for
 * resources.arsc and uncompressed native libraries. Writing is strictly sequential.
 */
final class ZipOut implements Closeable {

    private static final int LFH_SIG = 0x04034b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int ALIGN_EXTRA_ID = 0xD935;

    private static final class Rec {
        byte[] name;
        int flags;
        int method;
        int time;
        int date;
        long crc;
        long csize;
        long size;
        long offset;
    }

    private final FileChannel out;
    private final byte[] buf = new byte[1 << 16];
    private int bufLen;
    private long pos;
    private final List<Rec> records = new ArrayList<Rec>();
    private boolean finished;

    ZipOut(FileChannel out) {
        this.out = out;
    }

    /** Appends a new entry stored without compression, with its data starting on an {@code align} boundary. */
    void addStored(String name, byte[] data, int dosTime, int dosDate, int align) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        Rec r = header(name, 0x0800, 0, dosTime, dosDate, crc.getValue(), data.length, data.length, align);
        write(data, 0, data.length);
        records.add(r);
    }

    /** Copies an entry's raw bytes from {@code in} without touching them. */
    void addRaw(ZipIndex.Entry e, FileChannel in, long dataOffset, int align) throws IOException {
        int alignment = e.method == 0 ? align : 1;
        Rec r = header(e.name, e.flags & 0x0800, e.method, e.dosTime, e.dosDate,
                e.crc, e.compressedSize, e.size, alignment);
        copyFrom(in, dataOffset, e.compressedSize);
        records.add(r);
    }

    /** Writes the central directory and end record. Does not close the channel. */
    void finish() throws IOException {
        if (finished) {
            return;
        }
        if (records.size() > 0xFFFE) {
            throw new MalformedApkException("Too many entries for a non-ZIP64 archive");
        }
        long cdStart = pos;
        for (Rec r : records) {
            u32(CD_SIG);
            u16(0x0014);          // version made by
            u16(20);              // version needed
            u16(r.flags);
            u16(r.method);
            u16(r.time);
            u16(r.date);
            u32(r.crc);
            u32(r.csize);
            u32(r.size);
            u16(r.name.length);
            u16(0);               // extra length
            u16(0);               // comment length
            u16(0);               // disk number
            u16(0);               // internal attrs
            u32(0);               // external attrs
            u32(r.offset);
            write(r.name, 0, r.name.length);
        }
        long cdSize = pos - cdStart;
        u32(EOCD_SIG);
        u16(0);
        u16(0);
        u16(records.size());
        u16(records.size());
        u32(cdSize);
        u32(cdStart);
        u16(0);
        flushBuf();
        finished = true;
    }

    @Override
    public void close() throws IOException {
        // The caller owns the channel.
        if (!finished) {
            flushBuf();
        }
    }

    // ------------------------------------------------------------------ internals

    private Rec header(String name, int flags, int method, int time, int date,
                       long crc, long csize, long size, int align) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFFFF) {
            throw new MalformedApkException("Entry name too long");
        }
        if (csize > 0xFFFFFFFEL || size > 0xFFFFFFFEL || pos > 0xFFFFFFFEL) {
            throw new MalformedApkException("Entry too large for a non-ZIP64 archive");
        }
        int pad = 0;
        if (align > 1) {
            long dataStart = pos + 30 + nameBytes.length;
            int rem = (int) (dataStart % align);
            pad = rem == 0 ? 0 : align - rem;
            if (pad != 0 && pad < 4) {
                pad += align; // an extra-field record needs at least 4 bytes
            }
        }
        Rec r = new Rec();
        r.name = nameBytes;
        r.flags = flags;
        r.method = method;
        r.time = time;
        r.date = date;
        r.crc = crc;
        r.csize = csize;
        r.size = size;
        r.offset = pos;

        u32(LFH_SIG);
        u16(20);
        u16(flags);
        u16(method);
        u16(time);
        u16(date);
        u32(crc);
        u32(csize);
        u32(size);
        u16(nameBytes.length);
        u16(pad);
        write(nameBytes, 0, nameBytes.length);
        if (pad > 0) {
            u16(ALIGN_EXTRA_ID);
            u16(pad - 4);
            byte[] zeros = new byte[pad - 4];
            write(zeros, 0, zeros.length);
        }
        return r;
    }

    private void copyFrom(FileChannel in, long from, long count) throws IOException {
        flushBuf();
        long remaining = count;
        long p = from;
        while (remaining > 0) {
            long n = in.transferTo(p, remaining, out);
            if (n <= 0) {
                n = slowCopy(in, p, remaining);
            }
            p += n;
            remaining -= n;
            pos += n;
        }
    }

    private long slowCopy(FileChannel in, long from, long remaining) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate((int) Math.min(remaining, 1 << 18));
        int n = in.read(bb, from);
        if (n <= 0) {
            throw new IOException("Unexpected end of input while copying");
        }
        bb.flip();
        while (bb.hasRemaining()) {
            out.write(bb);
        }
        return n;
    }

    private void u16(int v) throws IOException {
        byte[] b = {(byte) v, (byte) (v >>> 8)};
        write(b, 0, 2);
    }

    private void u32(long v) throws IOException {
        byte[] b = {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
        write(b, 0, 4);
    }

    private void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            if (bufLen == buf.length) {
                flushBuf();
            }
            int n = Math.min(len, buf.length - bufLen);
            System.arraycopy(b, off, buf, bufLen, n);
            bufLen += n;
            off += n;
            len -= n;
            pos += n;
        }
    }

    private void flushBuf() throws IOException {
        if (bufLen == 0) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, bufLen).order(ByteOrder.LITTLE_ENDIAN);
        while (bb.hasRemaining()) {
            out.write(bb);
        }
        bufLen = 0;
    }
}
