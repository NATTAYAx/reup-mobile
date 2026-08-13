package app.reup.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// ─── Wall.kt — a direct port of src/lib/tz.ts ────────────────────────────────
//
// This is deliberately NOT idiomatic Kotlin. Every function here mirrors its
// TypeScript counterpart line for line, including the parts that look redundant,
// because the desktop implementation is the specification and any "improvement"
// made while porting is an untested behaviour change wearing a nice hat.
//
// The one that matters most is wallToEpochMs. kotlinx-datetime's own
// LocalDateTime.toInstant() resolves daylight-saving gaps and overlaps with its
// own rules, which are reasonable and are NOT the rules tz.ts uses. Using it
// would pass most tests and quietly disagree twice a year, in the two hours a
// year hardest to reproduce on purpose. So the two-pass probe is reimplemented
// here instead.

/**
 * A wall-clock reading in some zone. Mirrors the `Wall` interface in tz.ts.
 * [dow] is 0 = Sunday, matching JavaScript's Date.getDay().
 */
data class Wall(
    val y: Int,
    val mo: Int,
    val d: Int,
    val h: Int,
    val mi: Int,
    val s: Int,
    val dow: Int,
)

/** What the clock on the wall reads in [zone] at this instant. */
internal fun wallClock(epochMs: Long, zone: TimeZone): Wall {
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    return Wall(
        y = ldt.year,
        mo = ldt.monthNumber,
        d = ldt.dayOfMonth,
        h = ldt.hour,
        mi = ldt.minute,
        s = ldt.second,
        dow = ldt.dayOfWeek.isoDayNumber % 7, // ISO Monday=1..Sunday=7 -> Sunday=0
    )
}

/**
 * The real instant at which the clock on the wall reads this.
 *
 * Two passes, exactly as tz.ts does it: the first assumes the offset that
 * applies at the same numbers read as UTC; the second only changes the answer
 * within a day of a daylight-saving change, when the first guess can land on
 * the wrong side of the switch.
 */
internal fun wallToEpochMs(w: Wall, zone: TimeZone): Long {
    val asIfUtc = LocalDateTime(w.y, w.mo, w.d, w.h, w.mi, w.s)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()

    val first = zone.offsetAt(Instant.fromEpochMilliseconds(asIfUtc)).totalSeconds * 1000L
    val candidate = asIfUtc - first
    val second = zone.offsetAt(Instant.fromEpochMilliseconds(candidate)).totalSeconds * 1000L

    return if (second == first) candidate else asIfUtc - second
}

/**
 * Calendar-day arithmetic, which is not the same as adding 86,400,000 ms once a
 * zone with daylight saving is in play. Time of day is carried across.
 */
internal fun addDays(w: Wall, n: Int): Wall {
    val date = LocalDate(w.y, w.mo, w.d).plus(n, DateTimeUnit.DAY)
    return Wall(
        y = date.year,
        mo = date.monthNumber,
        d = date.dayOfMonth,
        h = w.h, mi = w.mi, s = w.s,
        dow = date.dayOfWeek.isoDayNumber % 7,
    )
}

/** Same day, different time of day. */
internal fun atTime(w: Wall, h: Int, mi: Int, s: Int = 0): Wall =
    Wall(y = w.y, mo = w.mo, d = w.d, h = h, mi = mi, s = s, dow = w.dow)

private val DATE_STR = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")

/**
 * "YYYY-MM-DD" to a wall clock at that date and time, or null if unparseable.
 *
 * NOTE ON A DELIBERATE DIVERGENCE: tz.ts accepts a day up to 31 for any month
 * and lets Date.UTC roll it over, so "2026-02-31" silently becomes 3 March.
 * LocalDate throws instead, and this returns null. That is a bug fix rather
 * than a port, and it is the only one in this file. Every anchor_date the app
 * writes is a real date, so no vector exercises it; if one ever does, this is
 * where the two implementations part company.
 */
internal fun dateStrToWall(dateStr: String, h: Int = 0, mi: Int = 0, s: Int = 0): Wall? {
    val m = DATE_STR.matchEntire(dateStr.trim()) ?: return null
    val y = m.groupValues[1].toInt()
    val mo = m.groupValues[2].toInt()
    val d = m.groupValues[3].toInt()
    if (y == 0 || mo < 1 || mo > 12 || d < 1 || d > 31) return null
    val date = runCatching { LocalDate(y, mo, d) }.getOrNull() ?: return null
    return Wall(y = date.year, mo = date.monthNumber, d = date.dayOfMonth, h = h, mi = mi, s = s, dow = 0)
}

/** Resolve an IANA zone id, falling back to [fallback] when it is null or unknown. */
internal fun resolveZone(id: String?, fallback: TimeZone): TimeZone {
    if (id.isNullOrBlank()) return fallback
    return runCatching { TimeZone.of(id) }.getOrDefault(fallback)
}
