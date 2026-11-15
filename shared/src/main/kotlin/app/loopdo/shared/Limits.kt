package app.loopdo.shared

/** Fixed numbers from the design spec. Every rule reads them from here. */
object Limits {
    /** Latest allowed day end, in hours (spec 6.1: 00:00–08:00 inclusive). */
    const val DAY_END_MAX_HOUR = 8
    const val DEFAULT_DAY_END_HOUR = 4

    /** Consecutive successful days that earn one freeze (spec 6.4). */
    const val FREEZE_EARN_RUN = 7
    /** Most freezes one streak can hold (spec 6.4). */
    const val FREEZE_CAP = 2

    /** Slack around a day boundary when judging an offline tick (spec 6.2). */
    const val TAMPER_TOLERANCE_SECONDS = 120L

    const val MAX_PHOTOS_PER_ENTRY = 4
    val MOOD_RANGE = 1..5
}
