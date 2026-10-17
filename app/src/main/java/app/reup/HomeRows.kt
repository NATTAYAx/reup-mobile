// app/src/main/java/app/reup/HomeRows.kt
package app.reup

import app.reup.core.Alarm
import app.reup.core.HomeRow
import app.reup.core.ScheduledTask
import app.reup.core.cadencePhrase
import app.reup.core.homeOrder
import app.reup.core.longCycle
import app.reup.core.recurs
import app.reup.core.tickedToday
import app.reup.sync.TaskInfo
import app.reup.sync.isDoneNow
import app.reup.sync.isoMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * The tasks as rows, in the order homeOrder gives them: what the Today tab and
 * the widget are both drawn from.
 *
 * It used to live inside the home screen's model. The widget is drawn when the
 * app is not open, from a receiver, and two copies of "which rows are done" is
 * how the home screen and the tab it opens would come to disagree about it.
 *
 * @param next each task's own next ring, from nextPerTask
 */
fun homeRows(
    tasks: List<ScheduledTask>,
    infos: Map<String, TaskInfo>,
    next: List<Alarm>,
    now: Instant,
    zone: TimeZone,
): List<HomeRow> {
    val nowIso = isoMillis(now.toEpochMilliseconds())
    val done = tasks.map { it.id }.filter { isDoneNow(infos[it]?.completedUntil, nowIso) }.toSet()
    val order = homeOrder(tasks.map { it.id }, next.map { it.taskId }, done)
    val soonest = HashMap<String, Alarm>()
    for (a in next) if (!soonest.containsKey(a.taskId)) soonest[a.taskId] = a
    val byId = tasks.associateBy { it.id }
    return order.mapNotNull { id ->
        val task = byId[id] ?: return@mapNotNull null
        val info = infos[id]
        val alarm = soonest[id]
        HomeRow(
            id = id,
            name = info?.name ?: "งาน",
            fireAt = alarm?.fireAt,
            silent = alarm?.silent ?: false,
            done = id in done,
            resetAt = alarm?.resetAt,
            doneToday = tickedToday(info?.completedAt, now, zone),
            paused = task.pausedUntil?.let { it > now } == true,
            cadence = cadencePhrase(task.spec),
            category = info?.category,
            recurring = recurs(task.spec),
            longCycle = longCycle(task.spec),
            createdAt = info?.createdAt,
        )
    }
}
