package app.littleclone.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Describes one clone: which package it is made from and what it becomes. */
public final class CloneSpec {
    private static final Pattern PACKAGE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern TAG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** Package name of the app being cloned. */
    public final String oldPackage;
    /** Package name the clone will be installed as. */
    public final String newPackage;
    /** Short unique suffix (e.g. "lc2") used to de-duplicate names that cannot be prefix-renamed. */
    public final String tag;
    /** Label shown under the launcher icon, or null to keep the original label. */
    public final String label;
    /** Native ABIs in order of preference; the first one present in an APK wins. */
    public final List<String> abiOrder;

    public CloneSpec(String oldPackage, String newPackage, String tag, String label, List<String> abiOrder) {
        if (oldPackage == null || !PACKAGE.matcher(oldPackage).matches()) {
            throw new IllegalArgumentException("Invalid original package: " + oldPackage);
        }
        if (newPackage == null || !PACKAGE.matcher(newPackage).matches() || newPackage.length() > 127) {
            throw new IllegalArgumentException("Invalid clone package: " + newPackage);
        }
        if (tag == null || !TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("Invalid tag: " + tag);
        }
        this.oldPackage = oldPackage;
        this.newPackage = newPackage;
        this.tag = tag;
        this.label = (label == null || label.trim().isEmpty()) ? null : label.trim();
        this.abiOrder = Collections.unmodifiableList(
                abiOrder == null ? new ArrayList<String>() : new ArrayList<String>(abiOrder));
    }
}
