package app.littleclone.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable model of an Android binary XML file (AndroidManifest.xml inside an APK).
 *
 * <p>Only start-element chunks and the string pool are modelled; everything else (namespaces,
 * end elements, CDATA, the resource map) is kept byte-for-byte and written back unchanged.
 * Chunk and file sizes are recomputed on write.
 */
final class AxmlDocument {
    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    static final int TYPE_XML = 0x0003;
    static final int TYPE_RESOURCE_MAP = 0x0180;
    static final int TYPE_END_ELEMENT = 0x0103;
    static final int TYPE_START_ELEMENT = 0x0102;

    /** Attribute value type: reference to a string in the pool. */
    static final int VALUE_STRING = 0x03;

    static final class Attr {
        int ns;
        int name;
        int raw;
        int type;
        int data;

        Attr(int ns, int name, int raw, int type, int data) {
            this.ns = ns;
            this.name = name;
            this.raw = raw;
            this.type = type;
            this.data = data;
        }
    }

    static final class StartElement {
        int lineNo;
        int comment;
        int ns;
        int name;
        int idIndex;
        int classIndex;
        int styleIndex;
        final List<Attr> attrs = new ArrayList<Attr>();
    }

    /** One top-level chunk in document order. Exactly one field is set. */
    static final class Part {
        final byte[] raw;
        final StartElement element;
        final boolean pool;

        Part(byte[] raw, StartElement element, boolean pool) {
            this.raw = raw;
            this.element = element;
            this.pool = pool;
        }

        int rawType() {
            return raw == null ? -1 : (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
        }
    }

    StringPool pool;
    int[] resourceIds = new int[0];
    final List<Part> parts = new ArrayList<Part>();

    // ------------------------------------------------------------------ parsing

    static AxmlDocument parse(byte[] data) {
        if (data.length < 8) {
            throw new MalformedApkException("Manifest is truncated");
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int type = b.getShort(0) & 0xFFFF;
        int headerSize = b.getShort(2) & 0xFFFF;
        long fileSize = b.getInt(4) & 0xFFFFFFFFL;
        if (type != TYPE_XML || headerSize != 8) {
            throw new MalformedApkException("AndroidManifest.xml is not binary XML (is it already text?)");
        }
        int limit = (int) Math.min(fileSize, data.length);

        AxmlDocument doc = new AxmlDocument();
        int pos = headerSize;
        while (pos + 8 <= limit) {
            int ctype = b.getShort(pos) & 0xFFFF;
            int chdr = b.getShort(pos + 2) & 0xFFFF;
            long csize = b.getInt(pos + 4) & 0xFFFFFFFFL;
            if (csize < chdr || csize < 8 || pos + csize > limit) {
                throw new MalformedApkException("Corrupt manifest chunk at offset " + pos);
            }
            int size = (int) csize;
            if (ctype == StringPool.TYPE) {
                if (doc.pool != null) {
                    throw new MalformedApkException("Manifest has more than one string pool");
                }
                doc.pool = StringPool.parse(data, pos, size);
                doc.parts.add(new Part(null, null, true));
            } else if (ctype == TYPE_START_ELEMENT) {
                doc.parts.add(new Part(null, parseElement(b, pos, size), false));
            } else {
                byte[] raw = new byte[size];
                System.arraycopy(data, pos, raw, 0, size);
                if (ctype == TYPE_RESOURCE_MAP) {
                    int n = (size - 8) / 4;
                    int[] ids = new int[n];
                    for (int i = 0; i < n; i++) {
                        ids[i] = b.getInt(pos + 8 + 4 * i);
                    }
                    doc.resourceIds = ids;
                }
                doc.parts.add(new Part(raw, null, false));
            }
            pos += size;
        }
        if (doc.pool == null) {
            throw new MalformedApkException("Manifest has no string pool");
        }
        return doc;
    }

    private static StartElement parseElement(ByteBuffer b, int pos, int size) {
        if (size < 36) {
            throw new MalformedApkException("Corrupt start-element chunk");
        }
        StartElement e = new StartElement();
        e.lineNo = b.getInt(pos + 8);
        e.comment = b.getInt(pos + 12);
        e.ns = b.getInt(pos + 16);
        e.name = b.getInt(pos + 20);
        int attrStart = b.getShort(pos + 24) & 0xFFFF;
        int attrSize = b.getShort(pos + 26) & 0xFFFF;
        int attrCount = b.getShort(pos + 28) & 0xFFFF;
        e.idIndex = b.getShort(pos + 30) & 0xFFFF;
        e.classIndex = b.getShort(pos + 32) & 0xFFFF;
        e.styleIndex = b.getShort(pos + 34) & 0xFFFF;
        if (attrSize != 20) {
            throw new MalformedApkException("Unexpected attribute size " + attrSize);
        }
        int ap = pos + 16 + attrStart;
        if (ap + 20L * attrCount > pos + size) {
            throw new MalformedApkException("Attributes run past the end of their element");
        }
        for (int i = 0; i < attrCount; i++) {
            int base = ap + 20 * i;
            int valueSize = b.getShort(base + 12) & 0xFFFF;
            if (valueSize != 8) {
                throw new MalformedApkException("Unexpected typed-value size " + valueSize);
            }
            e.attrs.add(new Attr(
                    b.getInt(base),
                    b.getInt(base + 4),
                    b.getInt(base + 8),
                    b.get(base + 15) & 0xFF,
                    b.getInt(base + 16)));
        }
        return e;
    }

    // ------------------------------------------------------------------ writing

    byte[] toBytes() {
        List<byte[]> chunks = new ArrayList<byte[]>();
        int total = 8;
        for (Part p : parts) {
            byte[] c;
            if (p.pool) {
                c = pool.toChunk();
            } else if (p.element != null) {
                c = writeElement(p.element);
            } else {
                c = p.raw;
            }
            chunks.add(c);
            total += c.length;
        }
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) TYPE_XML);
        out.putShort((short) 8);
        out.putInt(total);
        for (byte[] c : chunks) {
            out.put(c);
        }
        return out.array();
    }

    private static byte[] writeElement(StartElement e) {
        int count = e.attrs.size();
        if (count > 0xFFFF) {
            throw new MalformedApkException("Too many attributes");
        }
        int size = 36 + 20 * count;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) TYPE_START_ELEMENT);
        b.putShort((short) 16);
        b.putInt(size);
        b.putInt(e.lineNo);
        b.putInt(e.comment);
        b.putInt(e.ns);
        b.putInt(e.name);
        b.putShort((short) 20);        // attribute start
        b.putShort((short) 20);        // attribute size
        b.putShort((short) count);
        b.putShort((short) e.idIndex);
        b.putShort((short) e.classIndex);
        b.putShort((short) e.styleIndex);
        for (Attr a : e.attrs) {
            b.putInt(a.ns);
            b.putInt(a.name);
            b.putInt(a.raw);
            b.putShort((short) 8);
            b.put((byte) 0);
            b.put((byte) a.type);
            b.putInt(a.data);
        }
        return b.array();
    }

    // ------------------------------------------------------------------ helpers

    List<StartElement> elements() {
        List<StartElement> out = new ArrayList<StartElement>();
        for (Part p : parts) {
            if (p.element != null) {
                out.add(p.element);
            }
        }
        return out;
    }

    String name(StartElement e) {
        return pool.get(e.name);
    }

    /** Resource id (0x01010001 = android:label, ...) attached to the attribute's name, or 0. */
    int resourceIdOf(Attr a) {
        return (a.name >= 0 && a.name < resourceIds.length) ? resourceIds[a.name] : 0;
    }

    /** Finds an attribute in the android: namespace by its local name. */
    Attr findAndroid(StartElement e, String localName) {
        for (Attr a : e.attrs) {
            if (a.ns >= 0 && a.name >= 0 && ANDROID_NS.equals(pool.get(a.ns))
                    && localName.equals(pool.get(a.name))) {
                return a;
            }
        }
        return null;
    }

    /** Finds an attribute that has no namespace (e.g. the manifest's {@code package}). */
    Attr findPlain(StartElement e, String localName) {
        for (Attr a : e.attrs) {
            if (a.ns < 0 && a.name >= 0 && localName.equals(pool.get(a.name))) {
                return a;
            }
        }
        return null;
    }

    /** String value of an attribute, or null when it is not a literal string. */
    String stringValue(Attr a) {
        if (a == null || a.type != VALUE_STRING || a.data < 0 || a.data >= pool.size()) {
            return null;
        }
        return pool.get(a.data);
    }

    void setString(Attr a, String value) {
        int i = pool.intern(value);
        a.raw = i;
        a.data = i;
        a.type = VALUE_STRING;
    }
}
