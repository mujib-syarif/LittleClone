package app.littleclone.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renames the application package recorded inside a compiled resource table (resources.arsc).
 *
 * <p>The name is stored in a fixed 128-char field of each package chunk, so it can be rewritten
 * in place without changing any size or offset. Keeping it in sync with the manifest matters for
 * code that calls {@code getResources().getIdentifier(name, type, getPackageName())}.
 */
public final class ArscPatcher {
    private static final int TYPE_TABLE = 0x0002;
    private static final int TYPE_PACKAGE = 0x0200;
    private static final int NAME_OFFSET = 12;
    private static final int NAME_CHARS = 128;

    private ArscPatcher() {
    }

    /**
     * Rewrites, in place, every package chunk named {@code oldPackage}.
     *
     * @return true if at least one package was renamed
     */
    public static boolean renamePackage(byte[] arsc, String oldPackage, String newPackage) {
        if (newPackage.length() >= NAME_CHARS) {
            throw new MalformedApkException("Package name too long for resources.arsc");
        }
        if (arsc.length < 12) {
            throw new MalformedApkException("resources.arsc is truncated");
        }
        ByteBuffer b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xFFFF) != TYPE_TABLE) {
            throw new MalformedApkException("resources.arsc has an unexpected header");
        }
        int headerSize = b.getShort(2) & 0xFFFF;
        long declared = b.getInt(4) & 0xFFFFFFFFL;
        int limit = (int) Math.min(declared, arsc.length);

        boolean changed = false;
        int pos = headerSize;
        while (pos + 8 <= limit) {
            int type = b.getShort(pos) & 0xFFFF;
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            if (size < 8 || pos + size > limit) {
                throw new MalformedApkException("Corrupt resources.arsc chunk at offset " + pos);
            }
            if (type == TYPE_PACKAGE && size >= NAME_OFFSET + NAME_CHARS * 2) {
                int nameAt = pos + NAME_OFFSET;
                if (readName(b, nameAt).equals(oldPackage)) {
                    for (int i = 0; i < NAME_CHARS; i++) {
                        char c = i < newPackage.length() ? newPackage.charAt(i) : 0;
                        b.putShort(nameAt + 2 * i, (short) c);
                    }
                    changed = true;
                }
            }
            pos += (int) size;
        }
        return changed;
    }

    private static String readName(ByteBuffer b, int at) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAME_CHARS; i++) {
            char c = (char) (b.getShort(at + 2 * i) & 0xFFFF);
            if (c == 0) {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
