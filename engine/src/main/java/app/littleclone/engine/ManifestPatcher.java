package app.littleclone.engine;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a binary AndroidManifest.xml so the app installs next to the original as a
 * different package.
 *
 * <p>Changing the {@code package} attribute alone is not enough, because Android resolves a lot
 * of manifest content relative to or against that name:
 * <ul>
 *   <li>relative component names such as {@code .MainActivity} are expanded against the package, so
 *       they are turned into fully-qualified names using the <em>original</em> package first;</li>
 *   <li>provider authorities and declared permissions must be unique device-wide, otherwise the
 *       install fails with a conflict against the original app;</li>
 *   <li>{@code sharedUserId} would tie the clone to the original's UID, so it is made distinct;</li>
 *   <li>the launcher label is replaced so the clone can be told apart.</li>
 * </ul>
 */
public final class ManifestPatcher {

    private static final int RES_ATTR_LABEL = 0x01010001;

    private static final Set<String> CLASS_ELEMENTS = new HashSet<String>(Arrays.asList(
            "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"));

    private static final String[] CLASS_ATTRS = {
            "name", "targetActivity", "parentActivityName", "backupAgent",
            "manageSpaceActivity", "appComponentFactory", "zygotePreloadName"};

    private static final Set<String> COMPONENT_ELEMENTS = new HashSet<String>(Arrays.asList(
            "application", "activity", "activity-alias", "service", "receiver", "provider"));

    private final AxmlDocument doc;
    private final CloneSpec spec;
    private final Map<String, String> permissionMap = new HashMap<String, String>();

    private ManifestPatcher(AxmlDocument doc, CloneSpec spec) {
        this.doc = doc;
        this.spec = spec;
    }

    /** Returns a patched copy of {@code axml}; the input array is not modified. */
    public static byte[] patch(byte[] axml, CloneSpec spec) {
        AxmlDocument doc = AxmlDocument.parse(axml);
        new ManifestPatcher(doc, spec).run();
        byte[] out = doc.toBytes();
        String pkg = readPackage(out);
        if (!spec.newPackage.equals(pkg)) {
            throw new MalformedApkException("Patched manifest failed verification");
        }
        return out;
    }

    /** Reads the {@code package} attribute of a binary manifest, or null if absent. */
    public static String readPackage(byte[] axml) {
        AxmlDocument d = AxmlDocument.parse(axml);
        for (AxmlDocument.StartElement e : d.elements()) {
            if ("manifest".equals(d.name(e))) {
                return d.stringValue(d.findPlain(e, "package"));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ main pass

    private void run() {
        List<AxmlDocument.StartElement> elements = doc.elements();
        AxmlDocument.StartElement root = null;
        for (AxmlDocument.StartElement e : elements) {
            if ("manifest".equals(doc.name(e))) {
                root = e;
                break;
            }
        }
        if (root == null) {
            throw new MalformedApkException("AndroidManifest.xml has no <manifest> element");
        }
        AxmlDocument.Attr pkgAttr = doc.findPlain(root, "package");
        String pkg = doc.stringValue(pkgAttr);
        if (pkg == null) {
            throw new MalformedApkException("AndroidManifest.xml has no package name");
        }
        if (!pkg.equals(spec.oldPackage)) {
            throw new MalformedApkException(
                    "Manifest package (" + pkg + ") is not the expected " + spec.oldPackage);
        }

        collectDeclaredPermissions(elements);
        for (AxmlDocument.StartElement e : elements) {
            rewrite(e);
        }
        doc.setString(pkgAttr, spec.newPackage);
        applyLabels();
    }

    private void collectDeclaredPermissions(List<AxmlDocument.StartElement> elements) {
        for (AxmlDocument.StartElement e : elements) {
            String tag = doc.name(e);
            if (tag.equals("permission") || tag.equals("permission-group") || tag.equals("permission-tree")) {
                String name = doc.stringValue(doc.findAndroid(e, "name"));
                if (name != null && !permissionMap.containsKey(name)) {
                    permissionMap.put(name, renameUnique(name));
                }
            }
        }
    }

    private void rewrite(AxmlDocument.StartElement e) {
        String tag = doc.name(e);

        if (CLASS_ELEMENTS.contains(tag)) {
            for (String attr : CLASS_ATTRS) {
                replace(e, attr, qualify(doc.stringValue(doc.findAndroid(e, attr))));
            }
        }

        if (tag.equals("manifest")) {
            AxmlDocument.Attr uid = doc.findAndroid(e, "sharedUserId");
            String v = doc.stringValue(uid);
            if (v != null) {
                doc.setString(uid, v + "." + spec.tag);
            }
        } else if (tag.equals("provider")) {
            AxmlDocument.Attr a = doc.findAndroid(e, "authorities");
            String v = doc.stringValue(a);
            if (v != null) {
                doc.setString(a, renameAuthorities(v));
            }
        } else if (tag.equals("permission") || tag.equals("permission-group") || tag.equals("permission-tree")) {
            replace(e, "name", permissionMap.get(doc.stringValue(doc.findAndroid(e, "name"))));
            if (tag.equals("permission")) {
                replace(e, "permissionGroup", mapPermission(doc.stringValue(doc.findAndroid(e, "permissionGroup"))));
            }
        } else if (tag.equals("uses-permission") || tag.equals("uses-permission-sdk-23")) {
            replace(e, "name", mapPermission(doc.stringValue(doc.findAndroid(e, "name"))));
        } else if (tag.equals("instrumentation")) {
            String target = doc.stringValue(doc.findAndroid(e, "targetPackage"));
            if (spec.oldPackage.equals(target)) {
                replace(e, "targetPackage", spec.newPackage);
            }
        }

        if (COMPONENT_ELEMENTS.contains(tag)) {
            replace(e, "permission", mapPermission(doc.stringValue(doc.findAndroid(e, "permission"))));
            replace(e, "readPermission", mapPermission(doc.stringValue(doc.findAndroid(e, "readPermission"))));
            replace(e, "writePermission", mapPermission(doc.stringValue(doc.findAndroid(e, "writePermission"))));
            replace(e, "process", replacePrefix(doc.stringValue(doc.findAndroid(e, "process"))));
            replace(e, "taskAffinity", replacePrefix(doc.stringValue(doc.findAndroid(e, "taskAffinity"))));
        }
    }

    /** Sets android:{attr} to {@code value} when the attribute exists, is a string, and the value changed. */
    private void replace(AxmlDocument.StartElement e, String attr, String value) {
        if (value == null) {
            return;
        }
        AxmlDocument.Attr a = doc.findAndroid(e, attr);
        String current = doc.stringValue(a);
        if (current != null && !current.equals(value)) {
            doc.setString(a, value);
        }
    }

    // ------------------------------------------------------------------ name rules

    /** Expands relative class names ({@code .Foo} or {@code Foo}) against the original package. */
    private String qualify(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.charAt(0) == '.') {
            return spec.oldPackage + v;
        }
        if (v.indexOf('.') < 0) {
            return spec.oldPackage + "." + v;
        }
        return v;
    }

    private boolean hasOldPrefix(String v) {
        return v.equals(spec.oldPackage)
                || v.startsWith(spec.oldPackage + ".")
                || v.startsWith(spec.oldPackage + ":");
    }

    /** Swaps the original package for the clone's when {@code v} starts with it; otherwise null. */
    private String replacePrefix(String v) {
        if (v == null || !hasOldPrefix(v)) {
            return null;
        }
        return spec.newPackage + v.substring(spec.oldPackage.length());
    }

    /** Like {@link #replacePrefix} but guarantees a different result by appending the clone tag. */
    private String renameUnique(String v) {
        String swapped = replacePrefix(v);
        return swapped != null ? swapped : v + "." + spec.tag;
    }

    private String renameAuthorities(String list) {
        String[] parts = list.split(";");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            String t = parts[i].trim();
            if (!t.isEmpty()) {
                t = renameUnique(t);
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private String mapPermission(String v) {
        if (v == null) {
            return null;
        }
        String mapped = permissionMap.get(v);
        if (mapped != null) {
            return mapped;
        }
        return replacePrefix(v);
    }

    // ------------------------------------------------------------------ labels

    private void applyLabels() {
        String label = spec.label;
        if (label == null) {
            return;
        }
        int labelName = findLabelNameIndex();
        int androidNs = doc.pool.indexOf(AxmlDocument.ANDROID_NS);
        Set<AxmlDocument.StartElement> launchers = findLauncherComponents();

        for (AxmlDocument.StartElement e : doc.elements()) {
            String tag = doc.name(e);
            if (tag.equals("application")) {
                AxmlDocument.Attr a = doc.findAndroid(e, "label");
                if (a != null) {
                    doc.setString(a, label);
                } else if (labelName >= 0 && androidNs >= 0) {
                    insertLabel(e, label, labelName, androidNs);
                }
            } else if (launchers.contains(e)) {
                AxmlDocument.Attr a = doc.findAndroid(e, "label");
                if (a != null) {
                    doc.setString(a, label);
                }
            }
        }
    }

    private int findLabelNameIndex() {
        for (int i = 0; i < doc.pool.size() && i < doc.resourceIds.length; i++) {
            if (doc.resourceIds[i] == RES_ATTR_LABEL && "label".equals(doc.pool.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private Set<AxmlDocument.StartElement> findLauncherComponents() {
        Set<AxmlDocument.StartElement> result =
                java.util.Collections.newSetFromMap(new IdentityHashMap<AxmlDocument.StartElement, Boolean>());
        Deque<AxmlDocument.StartElement> stack = new ArrayDeque<AxmlDocument.StartElement>();
        for (AxmlDocument.Part p : doc.parts) {
            if (p.element != null) {
                AxmlDocument.StartElement e = p.element;
                if (doc.name(e).equals("category")) {
                    String v = doc.stringValue(doc.findAndroid(e, "name"));
                    if ("android.intent.category.LAUNCHER".equals(v)
                            || "android.intent.category.LEANBACK_LAUNCHER".equals(v)) {
                        for (AxmlDocument.StartElement s : stack) { // iterates innermost first
                            String t = doc.name(s);
                            if (t.equals("activity") || t.equals("activity-alias")) {
                                result.add(s);
                                break;
                            }
                        }
                    }
                }
                stack.push(e);
            } else if (p.raw != null && p.rawType() == AxmlDocument.TYPE_END_ELEMENT) {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            }
        }
        return result;
    }

    /** Inserts android:label keeping attributes ordered by resource id, as the framework expects. */
    private void insertLabel(AxmlDocument.StartElement e, String label, int labelName, int androidNs) {
        int idx = doc.pool.intern(label);
        AxmlDocument.Attr na = new AxmlDocument.Attr(androidNs, labelName, idx, AxmlDocument.VALUE_STRING, idx);
        int at = e.attrs.size();
        for (int k = 0; k < e.attrs.size(); k++) {
            int rid = doc.resourceIdOf(e.attrs.get(k));
            if (rid != 0 && rid > RES_ATTR_LABEL) {
                at = k;
                break;
            }
        }
        e.attrs.add(at, na);
        // idIndex / classIndex / styleIndex are 1-based positions of the id/class/style attributes.
        if (e.idIndex != 0 && at <= e.idIndex - 1) {
            e.idIndex++;
        }
        if (e.classIndex != 0 && at <= e.classIndex - 1) {
            e.classIndex++;
        }
        if (e.styleIndex != 0 && at <= e.styleIndex - 1) {
            e.styleIndex++;
        }
    }
}
