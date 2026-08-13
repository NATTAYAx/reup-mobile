package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.fail

// ─── ScheduleVectorsTest ─────────────────────────────────────────────────────
//
// The desktop implementation is the specification. This replays it.
//
// Every case was produced by running getNextReset() from src/lib/countdown.ts
// over a matrix of task shapes, instants, task zones and app zones, recording
// what it answered. A red test here means the Kotlin port disagrees with the
// app that has been running correctly for months — so the Kotlin is wrong, not
// the vector.
//
// Note that app_zone is read per case rather than fixed. A task with no zone of
// its own floats with the app's, so "what does this task do" has no answer
// until the app zone is stated. Leaving it implicit is how the first draft of
// this vector file ended up encoding a build machine's clock settings as a
// silent assumption, and disagreeing by seven hours in 264 cases.
//
// This lives in jvmTest rather than commonTest for one boring reason: reading a
// file is trivial on the JVM and a research project in common code. The code
// under test is in commonMain, so it is the same code iOS will run.

@Serializable
private data class VectorFile(val count: Int, val cases: List<Case>)

@Serializable
private data class Case(
    val id: String,
    @SerialName("app_zone") val appZone: String,
    val now: String,
    val task: TaskJson,
    val expect: String?,
)

@Serializable
private data class TaskJson(
    @SerialName("reset_type") val resetType: String,
    @SerialName("reset_time") val resetTime: String? = null,
    @SerialName("reset_day") val resetDay: Int? = null,
    @SerialName("anchor_date") val anchorDate: String? = null,
    @SerialName("reset_interval_days") val resetIntervalDays: Int? = null,
    @SerialName("event_end") val eventEnd: String? = null,
    @SerialName("specific_date") val specificDate: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
) {
    fun toSpec() = ResetSpec(
        resetType = resetType,
        resetTime = resetTime,
        resetDay = resetDay,
        anchorDate = anchorDate,
        resetIntervalDays = resetIntervalDays,
        eventEnd = eventEnd,
        specificDate = specificDate,
        timeZone = timeZone,
    )
}

class ScheduleVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectors(): VectorFile {
        val raw = javaClass.classLoader
            .getResourceAsStream("schedule-vectors.json")
            ?.bufferedReader()
            ?.readText()
            ?: fail("schedule-vectors.json not found on the test classpath")
        return json.decodeFromString(raw)
    }

    @Test
    fun `kotlin agrees with the desktop implementation on every vector`() {
        val file = loadVectors()
        val failures = mutableListOf<String>()

        for (case in file.cases) {
            val now = Instant.parse(case.now)
            val appZone = TimeZone.of(case.appZone)
            val expected = case.expect?.let { Instant.parse(it) }

            // Written as an if/else rather than getOrElse { ... continue }
            // because `continue` inside an inline lambda needs Kotlin 2.2, and
            // this module targets 2.1.
            val result = runCatching { nextReset(case.task.toSpec(), now, appZone) }
            val thrown = result.exceptionOrNull()

            if (thrown != null) {
                failures += "${case.id}  threw ${thrown::class.simpleName}: ${thrown.message}  " +
                        "[${case.task.resetType} tz=${case.task.timeZone} app=${case.appZone} now=${case.now}]"
            } else {
                val actual = result.getOrNull()
                if (actual != expected) {
                    failures += "${case.id}  expected=$expected  actual=$actual  " +
                            "[${case.task.resetType} tz=${case.task.timeZone ?: "(floats)"} app=${case.appZone} " +
                            "time=${case.task.resetTime} day=${case.task.resetDay} " +
                            "anchor=${case.task.anchorDate} iv=${case.task.resetIntervalDays} " +
                            "end=${case.task.eventEnd} date=${case.task.specificDate} now=${case.now}]"
                }
            }
        }

        if (failures.isNotEmpty()) {
            // A bounded sample rather than all of them. A port that is wrong is
            // usually wrong in one systematic way, and thirty examples show the
            // pattern as well as three thousand do.
            fail(
                "${failures.size} of ${file.cases.size} vectors disagree.\n\n" +
                        failures.take(30).joinToString("\n") +
                        if (failures.size > 30) "\n... and ${failures.size - 30} more" else ""
            )
        }
    }

    @Test
    fun `the vector file is the one we think it is`() {
        val file = loadVectors()
        check(file.cases.size == file.count) { "count field disagrees with cases length" }
        check(file.cases.size >= 3000) { "expected at least 3000 cases, found ${file.cases.size}" }
        check(file.cases.map { it.appZone }.distinct().size >= 2) {
            "vectors cover only one app zone, so floating tasks are untested"
        }
    }
}