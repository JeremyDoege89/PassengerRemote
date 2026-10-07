package dev.passengerremote.protocol

/**
 * How much privilege the app currently has.
 *
 * SHELL covers Shizuku and any other adb-shell-level grant; it is deliberately
 * not called "Shizuku" because root provides the same abilities by a different
 * route and nothing above this layer should care which one is in play.
 */
enum class AccessTier {
    /** Default. Everything the app does with no special setup. */
    ROOTLESS,

    /** adb-shell-level, via Shizuku. Can dispatch system key events. */
    SHELL,

    /** Full root. Everything SHELL can do; nothing here needs more than that. */
    ROOT,
    ;

    fun atLeast(other: AccessTier): Boolean = ordinal >= other.ordinal
}

enum class FeatureId {
    MEDIA_TRANSPORT,
    NOW_PLAYING,
    ARTWORK,
    SEEK,
    QUEUE,
    VOLUME,
    DESTINATION,
    VOICE,
    HEAD_UNIT_APP_SWITCH,
}

enum class FeatureLevel {
    /** Works now, on this phone, with what's playing. */
    WORKS,

    /** Partly works, or works unreliably. [FeatureStatus.detail] says how. */
    LIMITED,

    /** Does not work at all right now. */
    BLOCKED,
}

/**
 * One line of the honest answer to "what can this app actually do in my car?"
 *
 * [improvedBy] is null for anything elevated permissions would NOT fix. That
 * distinction is the point of this model: a fixed-volume head unit and a
 * head-unit app switch stay broken at every tier, and saying so is more useful
 * than implying Shizuku is a general unlock.
 */
data class FeatureStatus(
    val id: FeatureId,
    val level: FeatureLevel,
    val detail: String,
    val improvedBy: AccessTier? = null,
    val improvementDetail: String? = null,
) {
    fun isImprovableFrom(current: AccessTier): Boolean =
        improvedBy != null && !current.atLeast(improvedBy)
}

data class CapabilityReport(
    val tier: AccessTier,
    val features: List<FeatureStatus>,
) {
    /** Features that would get better if the user granted more access. */
    fun improvable(): List<FeatureStatus> = features.filter { it.isImprovableFrom(tier) }

    fun statusOf(id: FeatureId): FeatureStatus? = features.firstOrNull { it.id == id }
}
