package app.reup

import app.reup.core.QuietHours
import app.reup.core.ResetSpec
import app.reup.core.ResetType
import app.reup.core.ScheduledTask

/**
 * The task list, hardcoded, until there is a database.
 *
 * This exists because two things need the same list and neither can own it: the
 * screen, and the receiver that runs when the app is not open. That is exactly
 * the shape of a problem a database solves, so this is a placeholder with a
 * known replacement rather than a design.
 *
 * Keeping it here also keeps phase 2b honest — the new thing being tested is
 * the alarm pipeline, and adding storage at the same time would mean two new
 * things and no way to tell which one broke.
 */
object Samples {

    val quiet = QuietHours(start = "23:00", end = "08:00")

    private val entries = listOf(
        Entry(
            id = "daily04",
            label = "เกมรีเซ็ตรายวัน ตี 4",
            spec = ResetSpec(ResetType.DAILY, resetTime = "04:00"),
        ),
        Entry(
            id = "weeklyMon",
            label = "กิจกรรมรายสัปดาห์ จันทร์ 5 โมงเช้า",
            spec = ResetSpec(ResetType.WEEKLY, resetDay = 1, resetTime = "05:00"),
            notifyBeforeMin = 30,
        ),
        Entry(
            id = "every3d",
            label = "ทุก 3 วัน 9 โมงเช้า",
            spec = ResetSpec(
                ResetType.CUSTOM_DAYS,
                anchorDate = "2026-08-01",
                resetIntervalDays = 3,
                resetTime = "09:00",
            ),
        ),
        Entry(
            id = "tokyo04",
            label = "เซิร์ฟเวอร์ญี่ปุ่น ตี 4 เวลาโตเกียว",
            spec = ResetSpec(ResetType.DAILY, resetTime = "04:00", timeZone = "Asia/Tokyo"),
        ),
    )

    /** What the scheduler needs. */
    val tasks: List<ScheduledTask> = entries.map {
        ScheduledTask(id = it.id, spec = it.spec, notifyBeforeMin = it.notifyBeforeMin)
    }

    /** What the notification text needs. The core module has no idea what
     *  anything is called, on purpose — names are presentation. */
    fun labelOf(id: String): String =
        entries.firstOrNull { it.id == id }?.label ?: id

    private data class Entry(
        val id: String,
        val label: String,
        val spec: ResetSpec,
        val notifyBeforeMin: Int? = null,
    )
}