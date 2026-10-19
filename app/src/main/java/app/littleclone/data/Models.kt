package app.littleclone.data

/** An installed app that can be used as the source of a clone. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val sizeBytes: Long,
    /** Absolute paths of the base APK followed by any split APKs. */
    val sourcePaths: List<String>,
) {
    val partCount: Int get() = sourcePaths.size
}

/** A clone this app created; persisted as JSON. */
data class CloneRecord(
    val originPackage: String,
    val originLabel: String,
    val clonePackage: String,
    val label: String,
    val index: Int,
    val createdAt: Long,
    val sizeBytes: Long,
)
