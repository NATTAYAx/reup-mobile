package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

// ─── ResetSchedule.kt — a direct port of getNextReset() in countdown.ts ──────
//
// WHY THIS FUNCTION TAKES `now` AS AN ARGUMENT
//
// The desktop version calls Date.now() inside itself. That is fine for a render
// loop and fatal for this codebase, for two separate reasons.
//
// The first is testing: a function that reads the clock can only be tested at
// the moment the test runs, so "what does a weekly reset do on the day the
// clocks go back" is not a test anyone can write. Passing the instant in turns
// 1,400 impossible tests into 1,400 ordinary ones.
//
// The second is the whole point of the mobile app. The scheduler does not ask
// "when is the next reset from now"; it asks "what are the next fifty reset
// instants from now", which means evaluating this function at times that are
// not now, repeatedly, walking forward. A function that insists on reading the
// clock cannot answer that at all.
//
// So: no clock reads below this line. The caller supplies the instant.

/**
 * The next moment this task resets, or null if it has none.
 *
 * @param now the instant to answer from
 * @param appZone the zone used when [ResetSpec.timeZone] is null
 */
fun nextReset(spec: ResetSpec, now: Instant, appZone: TimeZone): Instant? {
    val nowMs = now.toEpochMilliseconds()
    val zone = resolveZone(spec.timeZone, appZone)
    val nowWall = wallClock(nowMs, zone)

    return when (spec.resetType) {
        ResetType.DAILY ->
            nextDaily(nowWall, nowMs, timeOrEndOfDay(spec.resetTime), zone)

        ResetType.WEEKLY -> {
            val day = spec.resetDay ?: return null
            nextWeekly(nowWall, nowMs, day, timeOrEndOfDay(spec.resetTime), zone)
        }

        ResetType.BIWEEKLY -> {
            val anchor = spec.anchorDate ?: return null
            nextCycle(nowMs, anchor, 14, timeOrEndOfDay(spec.resetTime), zone)
        }

        ResetType.CUSTOM_DAYS -> {
            val anchor = spec.anchorDate ?: return null
            val interval = spec.resetIntervalDays ?: return null
            nextCycle(nowMs, anchor, interval, timeOrEndOfDay(spec.resetTime), zone)
        }

        // Kept for backward-compat with old DB rows only. New one-off tasks use
        // specific_date instead.
        ResetType.ONE_TIME -> {
            val raw = spec.eventEnd ?: return null
            parseLooseInstant(raw, zone)
        }

        ResetType.EVENT_WINDOW -> {
            val raw = (spec.eventEnd ?: return null).trim()
            when {
                // Case 1: an explicit UTC stamp. Unambiguous, parse directly.
                raw.contains('T') && raw.endsWith("Z") ->
                    runCatching { Instant.parse(raw) }.getOrNull()

                // Case 2: a bare date from the date picker. End of that day.
                DATE_ONLY.matches(raw) ->
                    momentInZone(raw, spec.resetTime, zone)

                // Case 3: a stamp carrying no zone of its own, read as the
                // task's zone.
                else -> parseLooseInstant(raw, zone)
            }
        }

        // With a time it is an appointment, without one it is a deadline for the
        // day. Both are one-off; only the precision differs.
        ResetType.SPECIFIC_DATE -> {
            val date = spec.specificDate ?: return null
            momentInZone(date, spec.resetTime, zone)
        }

        else -> null
    }
}

// ── internals ────────────────────────────────────────────────────────────────

private data class HM(val h: Int, val mi: Int)

private val HHMM = Regex("""^(\d{1,2}):(\d{2})$""")
private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val LOOSE_LOCAL = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$""")
private val HAS_OFFSET = Regex("""[+-]\d{2}:?\d{2}$""")

private fun parseHHMM(time: String?): HM? {
    if (time.isNullOrBlank()) return null
    val m = HHMM.matchEntire(time.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    if (h > 23 || mi > 59) return null
    return HM(h, mi)
}

/**
 * Repeating kinds need a concrete time to aim at; with none, aim at end of day.
 *
 * Note this is 23:59:00, while [momentInZone] uses 23:59:59 for the same idea.
 * That inconsistency exists in countdown.ts and is reproduced on purpose — the
 * vectors encode it, and changing it here would make the two apps disagree by
 * fifty-nine seconds on every all-day task.
 */
private fun timeOrEndOfDay(time: String?): HM = parseHHMM(time) ?: HM(23, 59)

/** A wall-clock moment in the task's zone, as a real instant. */
private fun momentInZone(dateStr: String, time: String?, zone: TimeZone): Instant? {
    val hhmm = parseHHMM(time)
    // No time means the whole day, which ends one second before the next one.
    val w = if (hhmm != null) {
        dateStrToWall(dateStr, hhmm.h, hhmm.mi, 0)
    } else {
        dateStrToWall(dateStr, 23, 59, 59)
    } ?: return null
    return Instant.fromEpochMilliseconds(wallToEpochMs(w, zone))
}

/** A timestamp that carries no zone of its own is read as the task's zone. */
private fun parseLooseInstant(raw: String, zone: TimeZone): Instant? {
    val s = raw.trim()
    if (s.isEmpty()) return null

    if (s.endsWith("Z") || s.endsWith("z") || HAS_OFFSET.containsMatchIn(s)) {
        // String overload, not the Char one: Kotlin's replace(Char, Char)
        // replaces every occurrence, and only the first space is a separator.
        val normalised = if (s.contains('T')) s else s.replaceFirst(" ", "T")
        return runCatching { Instant.parse(normalised) }.getOrNull()
    }

    val m = LOOSE_LOCAL.matchEntire(s)
    if (m != null) {
        val w = Wall(
            y = m.groupValues[1].toInt(),
            mo = m.groupValues[2].toInt(),
            d = m.groupValues[3].toInt(),
            h = m.groupValues[4].toInt(),
            mi = m.groupValues[5].toInt(),
            s = m.groupValues[6].ifEmpty { "0" }.toInt(),
            dow = 0,
        )
        return Instant.fromEpochMilliseconds(wallToEpochMs(w, zone))
    }

    // countdown.ts falls through to `new Date(s)` here, which accepts a long
    // tail of loose formats that no other runtime agrees on. Rather than guess
    // at which, this gives up. Nothing the app writes reaches this branch; if
    // something ever does it will surface as a null rather than as a wrong time.
    return runCatching { Instant.parse(s) }.getOrNull()
}

private fun nextDaily(now: Wall, nowMs: Long, hm: HM, zone: TimeZone): Instant {
    var ms = wallToEpochMs(atTime(now, hm.h, hm.mi), zone)
    if (ms <= nowMs) ms = wallToEpochMs(atTime(addDays(now, 1), hm.h, hm.mi), zone)
    return Instant.fromEpochMilliseconds(ms)
}

private fun nextWeekly(now: Wall, nowMs: Long, resetDay: Int, hm: HM, zone: TimeZone): Instant {
    var daysUntil = resetDay - now.dow
    if (daysUntil < 0) daysUntil += 7
    var ms = wallToEpochMs(atTime(addDays(now, daysUntil), hm.h, hm.mi), zone)
    // Landing on today but already past the time means it is next week.
    if (ms <= nowMs) ms = wallToEpochMs(atTime(addDays(now, daysUntil + 7), hm.h, hm.mi), zone)
    return Instant.fromEpochMilliseconds(ms)
}

private fun nextCycle(
    nowMs: Long,
    anchorDate: String,
    intervalDays: Int,
    hm: HM,
    zone: TimeZone,
): Instant? {
    if (anchorDate.isBlank() || intervalDays < 1) return null
    val anchor = dateStrToWall(anchorDate, hm.h, hm.mi) ?: return null

    // Whole cycles elapsed, then the boundary rebuilt as calendar days rather
    // than a fixed multiple of 86,400,000 ms — the two only agree in a zone with
    // no daylight saving.
    val cycleMs = intervalDays.toLong() * 86_400_000L
    val elapsed = nowMs - wallToEpochMs(anchor, zone)

    // floorDiv, not `/`. JavaScript's Math.floor rounds toward negative
    // infinity and Kotlin's division truncates toward zero, so for an anchor in
    // the future the two disagree by exactly one cycle.
    var n = elapsed.floorDiv(cycleMs) + 1
    if (n < 0) n = 0 // anchor still in the future: the first one is the anchor

    var ms = wallToEpochMs(addDays(anchor, (n * intervalDays).toInt()), zone)
    if (ms <= nowMs) ms = wallToEpochMs(addDays(anchor, ((n + 1) * intervalDays).toInt()), zone)
    return Instant.fromEpochMilliseconds(ms)
}
