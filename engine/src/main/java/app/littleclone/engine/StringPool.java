package app.littleclone.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android binary-XML string pool (chunk type 0x0001).
 *
 * <p>Strings are only ever appended, never reordered or removed, so every existing index in the
 * rest of the document (attribute names, resource-map positions, ...) stays valid.
 */
final class StringPool {
    static final int TYPE = 0x0001;
    private static final int UTF8_FLAG = 0x100;

    private final List<String> strings = new ArrayList<String>();
    private final Map<String, Integer> firstIndex = new HashMap<String, Integer>();
    private final boolean utf8;

    StringPool(boolean utf8) {
        this.utf8 = utf8;
    }

    int size() {
        return strings.size();
    }

    String get(int i) {
        if (i < 0 || i >= strings.size()) {
            throw new MalformedApkException("String index out of range: " + i);
        }
        return strings.get(i);
    }

    /** Index of the first string equal to {@code s}, or -1. */
    int indexOf(String s) {
        Integer i = firstIndex.get(s);
        return i == null ? -1 : i.intValue();
    }

    /** Returns the index of {@code s}, appending it to the pool if it is not there yet. */
    int intern(String s) {
        int i = indexOf(s);
        if (i >= 0) {
            return i;
        }
        strings.add(s);
        int idx = strings.size() - 1;
        firstIndex.put(s, idx);
        return idx;
    }

    private void addParsed(String s) {
        strings.add(s);
        if (!firstIndex.containsKey(s)) {
            firstIndex.put(s, strings.size() - 1);
        }
    }

    // ------------------------------------------------------------------ parsing

    static StringPool parse(byte[] d, int pos, int chunkSize) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getShort(pos + 2) & 0xFFFF;
        long count = b.getInt(pos + 8) & 0xFFFFFFFFL;
        long styleCount = b.getInt(pos + 12) & 0xFFFFFFFFL;
        long flags = b.getInt(pos + 16) & 0xFFFFFFFFL;
        long stringsStart = b.getInt(pos + 20) & 0xFFFFFFFFL;
        if (styleCount != 0) {
            throw new MalformedApkException("Styled string pools are not supported in manifests");
        }
        if (headerSize < 28 || count > 1_000_000L || stringsStart > chunkSize) {
            throw new MalformedApkException("Corrupt string pool header");
        }
        boolean isUtf8 = (flags & UTF8_FLAG) != 0;
        StringPool pool = new StringPool(isUtf8);
        int limit = pos + chunkSize;
        int offsetsPos = pos + headerSize;
        int base = pos + (int) stringsStart;
        if (offsetsPos + count * 4 > limit) {
            throw new MalformedApkException("Corrupt string pool offsets");
        }
        for (int i = 0; i < count; i++) {
            long off = b.getInt(offsetsPos + 4 * i) & 0xFFFFFFFFL;
            long p = base + off;
            if (p >= limit) {
                throw new MalformedApkException("String offset out of range");
            }
            pool.addParsed(isUtf8 ? readUtf8(d, (int) p, limit) : readUtf16(d, (int) p, limit));
        }
        return pool;
    }

    private static String readUtf8(byte[] d, int p, int limit) {
        int c = d[p++] & 0xFF;
        if ((c & 0x80) != 0) {
            p++; // two-byte UTF-16 length; value not needed
        }
        int b1 = d[p++] & 0xFF;
        int byteLen = b1;
        if ((b1 & 0x80) != 0) {
            byteLen = ((b1 & 0x7F) << 8) | (d[p++] & 0xFF);
        }
        if (p + byteLen > limit) {
            throw new MalformedApkException("UTF-8 string runs past the end of the pool");
        }
        return new String(d, p, byteLen, StandardCharsets.UTF_8);
    }

    private static String readUtf16(byte[] d, int p, int limit) {
        int len = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
        p += 2;
        if ((len & 0x8000) != 0) {
            int low = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
            p += 2;
            len = ((len & 0x7FFF) << 16) | low;
        }
        if (p + 2L * len > limit) {
            throw new MalformedApkException("UTF-16 string runs past the end of the pool");
        }
        return new String(d, p, len * 2, StandardCharsets.UTF_16LE);
    }

    // ------------------------------------------------------------------ writing

    byte[] toChunk() {
        int n = strings.size();
        byte[][] enc = new byte[n][];
        int[] offsets = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            enc[i] = utf8 ? encodeUtf8(strings.get(i)) : encodeUtf16(strings.get(i));
            offsets[i] = total;
            total += enc[i].length;
        }
        int pad = (4 - (total % 4)) % 4;
        int headerSize = 28;
        int stringsStart = headerSize + 4 * n;
        int size = stringsStart + total + pad;

        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) TYPE);
        b.putShort((short) headerSize);
        b.putInt(size);
        b.putInt(n);
        b.putInt(0);                       // style count
        b.putInt(utf8 ? UTF8_FLAG : 0);    // never "sorted": we append out of order
        b.putInt(stringsStart);
        b.putInt(0);                       // styles start
        for (int i = 0; i < n; i++) {
            b.putInt(offsets[i]);
        }
        for (int i = 0; i < n; i++) {
            b.put(enc[i]);
        }
        return b.array();
    }

    private static byte[] encodeUtf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int charLen = s.length();
        if (charLen > 0x7FFF || bytes.length > 0x7FFF) {
            throw new MalformedApkException("String too long for a manifest string pool");
        }
        ByteBuffer b = ByteBuffer.allocate(4 + bytes.length + 1);
        putLen8(b, charLen);
        putLen8(b, bytes.length);
        b.put(bytes);
        b.put((byte) 0);
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }

    private static void putLen8(ByteBuffer b, int len) {
        if (len > 0x7F) {
            b.put((byte) (0x80 | (len >> 8)));
        }
        b.put((byte) (len & 0xFF));
    }

    private static byte[] encodeUtf16(String s) {
        int len = s.length();
        byte[] chars = s.getBytes(StandardCharsets.UTF_16LE);
        boolean wide = len > 0x7FFF;
        ByteBuffer b = ByteBuffer.allocate((wide ? 4 : 2) + chars.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        if (wide) {
            b.putShort((short) (0x8000 | (len >>> 16)));
            b.putShort((short) (len & 0xFFFF));
        } else {
            b.putShort((short) len);
        }
        b.put(chars);
        b.putShort((short) 0);
        return b.array();
    }
}
