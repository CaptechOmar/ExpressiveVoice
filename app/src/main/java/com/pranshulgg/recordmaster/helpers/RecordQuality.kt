package com.pranshulgg.recordmaster.helpers

/**
 * Recording quality presets (AAC in an .m4a file). Higher quality = clearer sound but bigger files.
 * The default, [STANDARD], matches what the app always used before this setting existed.
 */
enum class RecordQuality(
    val key: String,
    val title: String,
    val bitRate: Int,
    val sampleRate: Int,
    val sizeNote: String
) {
    LOW("low", "Low", 64_000, 22_050, "~0.5 MB/min"),
    STANDARD("standard", "Standard", 128_000, 44_100, "~1 MB/min"),
    HIGH("high", "High", 192_000, 48_000, "~1.4 MB/min"),
    MAXIMUM("maximum", "Maximum", 256_000, 48_000, "~1.9 MB/min");

    /** e.g. "Standard · 128 kbps · ~1 MB/min" */
    val label: String get() = "$title · ${bitRate / 1000} kbps · $sizeNote"

    companion object {
        const val PREF_KEY = "record_quality"

        fun fromKey(key: String?): RecordQuality =
            entries.firstOrNull { it.key == key } ?: STANDARD

        /** The quality the user picked in Settings. */
        fun current(): RecordQuality = fromKey(PreferencesHelper.getString(PREF_KEY))
    }
}
