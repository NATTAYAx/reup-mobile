package app.reup.core

// ─── Task.kt ─────────────────────────────────────────────────────────────────
//
// Only the fields the reset engine reads. Not the full row.
//
// The desktop Task carries about twenty more columns — name, category, colour,
// completed_until, notify_before, trash state, sync metadata. None of them
// change WHEN a task resets, so none of them belong in a function whose whole
// job is answering that question. Keeping this narrow is what lets the vector
// test cover the engine completely: seven fields have a finite set of shapes,
// a whole database row does not.
//
// The storage layer will have its own wider type. This one is the argument to
// a calculation.

/** The reset kinds the engine understands. Values match the desktop DB strings. */
object ResetType {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val BIWEEKLY = "biweekly"
    const val CUSTOM_DAYS = "custom_days"
    const val ONE_TIME = "one_time"
    const val EVENT_WINDOW = "event_window"
    const val SPECIFIC_DATE = "specific_date"
}

data class ResetSpec(
    /** One of [ResetType]. Anything else yields no reset at all. */
    val resetType: String,

    /** "HH:MM" in the task's zone, or null meaning the whole day. */
    val resetTime: String? = null,

    /** 0 = Sunday. Weekly only. */
    val resetDay: Int? = null,

    /** "YYYY-MM-DD". Start of the counting for biweekly and custom_days. */
    val anchorDate: String? = null,

    /** Custom_days only. Must be at least 1. */
    val resetIntervalDays: Int? = null,

    /** Event_window and the legacy one_time. May be a UTC stamp, a loose local
     *  stamp, or a bare date. */
    val eventEnd: String? = null,

    /** "YYYY-MM-DD". Specific_date only. */
    val specificDate: String? = null,

    /**
     * IANA zone id, or null to float with whatever the app's zone is.
     *
     * Null is not "unknown", it is a choice: a task pinned to a zone is read in
     * that zone wherever the person happens to be, and a floating one moves
     * with them. A game server resetting at 04:00 Tokyo is the first; "take the
     * pills at 09:00" is the second.
     */
    val timeZone: String? = null,
)
