package app.littleclone.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The fixtures are real binary-XML manifests (UTF-8 and UTF-16 string pools) of a synthetic app
 * "com.example.game" that exercises the tricky parts: relative class names, self-declared
 * permissions, provider authorities, process/affinity names and launcher labels.
 */
public class ManifestPatcherTest {
    private static final String OLD = "com.example.game";
    private static final String NEW = "com.example.game.lc2";

    private static CloneSpec spec(String label) {
        return new CloneSpec(OLD, NEW, "lc2", label, Arrays.asList("arm64-v8a"));
    }

    static byte[] resource(String name) throws IOException {
        InputStream in = ManifestPatcherTest.class.getResourceAsStream("/" + name);
        assertNotNull("missing test resource " + name, in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String attr(AxmlDocument d, AxmlDocument.StartElement e, String name) {
        return d.stringValue(d.findAndroid(e, name));
    }

    private static List<AxmlDocument.StartElement> all(AxmlDocument d, String tag) {
        List<AxmlDocument.StartElement> out = new java.util.ArrayList<AxmlDocument.StartElement>();
        for (AxmlDocument.StartElement e : d.elements()) {
            if (d.name(e).equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    private void checkPatched(String fixture) throws IOException {
        byte[] in = resource(fixture);
        byte[] out = ManifestPatcher.patch(in, spec("Example Game 2"));
        assertEquals(OLD, ManifestPatcher.readPackage(in));
        assertEquals(NEW, ManifestPatcher.readPackage(out));

        AxmlDocument d = AxmlDocument.parse(out);
        assertEquals(AxmlDocument.parse(in).elements().size(), d.elements().size());

        AxmlDocument.StartElement app = all(d, "application").get(0);
        assertEquals(OLD + ".App", attr(d, app, "name"));
        assertEquals("Example Game 2", attr(d, app, "label"));

        List<AxmlDocument.StartElement> acts = all(d, "activity");
        assertEquals(OLD + ".MainActivity", attr(d, acts.get(0), "name"));
        assertEquals("Example Game 2", attr(d, acts.get(0), "label"));
        assertEquals(OLD + ".Other", attr(d, acts.get(1), "name"));
        assertNull("non-launcher label must stay a resource reference", attr(d, acts.get(1), "label"));
        assertEquals(OLD + ".MainActivity", attr(d, acts.get(1), "parentActivityName"));
        assertEquals(NEW + ".aff", attr(d, acts.get(1), "taskAffinity"));

        AxmlDocument.StartElement alias = all(d, "activity-alias").get(0);
        assertEquals(OLD + ".MainActivity", attr(d, alias, "targetActivity"));
        assertEquals("Example Game 2", attr(d, alias, "label"));

        List<AxmlDocument.StartElement> services = all(d, "service");
        assertEquals(OLD + ".Svc", attr(d, services.get(0), "name"));
        assertEquals(":remote", attr(d, services.get(0), "process"));
        assertEquals(NEW + ":push", attr(d, services.get(1), "process"));
        assertEquals(NEW + ".permission.C2D_MESSAGE", attr(d, services.get(1), "permission"));

        AxmlDocument.StartElement receiver = all(d, "receiver").get(0);
        assertEquals("com.google.android.c2dm.permission.SEND", attr(d, receiver, "permission"));

        AxmlDocument.StartElement provider = all(d, "provider").get(0);
        assertEquals(NEW + ".fileprovider;com.facebook.app.FacebookContentProvider123.lc2",
                attr(d, provider, "authorities"));
        assertEquals("vendor.shared.PERM.lc2", attr(d, provider, "readPermission"));

        List<AxmlDocument.StartElement> declared = all(d, "permission");
        assertEquals(NEW + ".permission.C2D_MESSAGE", attr(d, declared.get(0), "name"));
        assertEquals(NEW + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", attr(d, declared.get(1), "name"));
        assertEquals("vendor.shared.PERM.lc2", attr(d, declared.get(2), "name"));

        List<AxmlDocument.StartElement> used = all(d, "uses-permission");
        assertEquals("android.permission.INTERNET", attr(d, used.get(0), "name"));
        assertEquals(NEW + ".permission.C2D_MESSAGE", attr(d, used.get(1), "name"));

        AxmlDocument.StartElement meta = all(d, "meta-data").get(0);
        assertEquals(OLD + ".foo", attr(d, meta, "value")); // arbitrary values are never rewritten
    }

    @Test
    public void patchesUtf8Manifest() throws IOException {
        checkPatched("manifest_utf8.xml");
    }

    @Test
    public void patchesUtf16Manifest() throws IOException {
        checkPatched("manifest_utf16.xml");
    }

    @Test
    public void insertsApplicationLabelWhenMissing() throws IOException {
        byte[] out = ManifestPatcher.patch(resource("manifest_nolabel.xml"), spec("Clone"));
        AxmlDocument d = AxmlDocument.parse(out);
        AxmlDocument.StartElement app = all(d, "application").get(0);
        assertEquals("Clone", attr(d, app, "label"));
        // attributes must stay sorted by resource id: the framework's attribute lookup relies on it
        int last = 0;
        for (AxmlDocument.Attr a : app.attrs) {
            int id = d.resourceIdOf(a);
            assertTrue("attributes out of order", id >= last);
            last = id;
        }
    }

    @Test
    public void keepsOriginalLabelWhenNoneRequested() throws IOException {
        byte[] out = ManifestPatcher.patch(resource("manifest_utf8.xml"), spec(null));
        AxmlDocument d = AxmlDocument.parse(out);
        assertNull(attr(d, all(d, "application").get(0), "label")); // still a resource reference
    }

    @Test(expected = MalformedApkException.class)
    public void rejectsManifestOfAnotherPackage() throws IOException {
        ManifestPatcher.patch(resource("manifest_utf8.xml"),
                new CloneSpec("org.other.app", "org.other.app.lc1", "lc1", null, Arrays.asList("arm64-v8a")));
    }

    @Test(expected = MalformedApkException.class)
    public void rejectsPlainTextManifest() {
        ManifestPatcher.patch("<manifest/>".getBytes(), spec(null));
    }
}
