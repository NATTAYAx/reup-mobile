package app.reup.ui

import android.content.Context
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.reup.Repo
import app.reup.Scheduler
import app.reup.core.resetTimeParts
import app.reup.core.resetTimeText
import app.reup.sync.TASK_EDITABLE
import app.reup.sync.TaskDraft
import app.reup.sync.taskProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

// ─── TaskFormModel.kt — what the task form is drawn from ─────────────────────
//
// The old AddTaskActivity, moved rather than rewritten: the same five kinds of
// task, the same checks before a save, the same care never to clear a column a
// newer desktop wrote, one form for adding and for editing. What changed is
// that nothing is typed that can be picked. The time is a clock, the date a
// calendar, the number of days a stepper, and the only box left is the name.
//
// WHY IT REFUSES WHERE THE DESKTOP FORM DOES NOT
//
// The desktop form defaults its way past most of what taskProblems can find,
// because every control on it has a default. This form now has defaults too -
// a weekly task starts on Monday, a dated one on today - but it still asks the
// shared checks before saving, because a weekly task saved with no day is a
// row that sits in the list looking normal and never rings once.

class TaskFormModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** The task being changed, by uid; null for a new one. */
    private val uid: String?,
    /**
     * Leave the form, with what to say about it where it goes back to -
     * "เพิ่มงานแล้ว · เช็คอินรายวัน" - or null when there is nothing to say.
     */
    private val done: (said: String?) -> Unit,
) {

    val editing: Boolean get() = uid != null

    // ── what the form holds ─────────────────────────────────────────────────

    val name = TextFieldState()

    /** Every how many days, for ทุก N วัน. Two to start: every other day. */
    val interval = TextFieldState("2")

    var type by mutableStateOf("daily")
        private set

    /** Sunday is zero, as reset_day counts. Monday to start, as it always has. */
    var day by mutableIntStateOf(1)
        private set

    /** `YYYY-MM-DD`, for วันที่กำหนด. */
    var date by mutableStateOf(LocalDate.now().toString())
        private set

    /** "04:00", or null: no time, the end of the day, as on the desktop. */
    var time by mutableStateOf<String?>(null)
        private set

    var priority by mutableStateOf(false)
        private set
    var urgent by mutableStateOf(false)
        private set

    /** May ring in quiet hours: the desktop's tasks.ring_in_quiet. */
    var wake by mutableStateOf(false)
        private set

    /** `YYYY-MM-DD`, read again whenever the screen comes back. */
    var today by mutableStateOf(LocalDate.now().toString())
        private set

    val tomorrow: String
        get() = LocalDate.parse(today).plusDays(1).toString()

    // ── the screen's own ────────────────────────────────────────────────────

    /** The row has been read - or there is none to read, for a new task. */
    var loaded by mutableStateOf(uid == null)
        private set

    /** Reading, writing or removing: every button waits. */
    var busy by mutableStateOf(false)
        private set

    /** A save in flight, which the button says in words. */
    var saving by mutableStateOf(false)
        private set

    /**
     * Nothing worth saving: the row is gone, or it is a kind of task this
     * screen cannot show. Deleting still works - a task that cannot be edited
     * here is still one that can be thrown away.
     */
    var blocked by mutableStateOf(false)
        private set

    var status by mutableStateOf<FormStatus?>(null)
        private set

    /** Bumped when the name box should take the cursor. */
    var focusName by mutableIntStateOf(0)
        private set

    /** Told to go: nothing more is pressed while the screen slides away. */
    private var leaving = false

    /** One of the five kinds this form can show. */
    val known: Boolean
        get() = TYPES.any { it.first == type }

    // ── opening ─────────────────────────────────────────────────────────────

    /**
     * Fill the form from the row, or, for a new task, put the cursor in the
     * name, which is the one thing that has to be typed.
     *
     * A missing row is a real answer on a device that syncs: the task may have
     * been deleted on the desktop between the list being drawn and this row
     * being opened. Saying so beats an empty form that silently turns into a
     * new task.
     */
    fun load() {
        val id = uid
        if (id == null) {
            focusName++
            return
        }
        busy = true
        scope.launch {
            try {
                val fields = Repo.open(ctx).taskForEdit(id)
                if (fields == null) {
                    status = warn(GONE)
                    blocked = true
                    return@launch
                }
                name.setTextAndPlaceCursorAtEnd(fields["name"] ?: "")
                // "9:00" from an older row opens at nine and is saved back as
                // "09:00", rather than refused the way a typed one was.
                time = resetTimeParts(fields["reset_time"])?.let { (h, m) -> resetTimeText(h, m) }
                fields["reset_interval_days"]?.takeIf { it.isNotBlank() }?.let { interval.setTextAndPlaceCursorAtEnd(it) }
                fields["specific_date"]?.takeIf { it.isNotBlank() }?.let { date = it }
                type = fields["reset_type"] ?: "daily"
                day = fields["reset_day"]?.toIntOrNull()?.takeIf { it in 0..6 } ?: 1
                priority = fields["is_priority"] == "1"
                urgent = fields["is_urgent"] == "1"
                wake = fields["ring_in_quiet"] == "1"
                // A task made on the desktop can be a kind this form has no
                // chip for. Showing it as something else would be a lie that
                // saving would then make true.
                if (!known) {
                    status = warn(UNSUPPORTED)
                    blocked = true
                }
            } catch (e: Exception) {
                status = warn("เปิดงานนี้ไม่ได้ ${e.message ?: e.toString()}")
                blocked = true
            } finally {
                busy = false
                loaded = true
            }
        }
    }

    /** Coming back to the screen: it may be another day. */
    fun resume() {
        today = LocalDate.now().toString()
    }

    // ── typing and picking ──────────────────────────────────────────────────

    /** Typing in the name: the line about the form as it was goes. */
    val typing: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            if (asCharSequence().toString() != originalText.toString()) settle()
        }
    }

    /** Whole days, three digits at most; anything else is not taken in. */
    val intervalInput: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            val typed = asCharSequence().toString()
            if (typed.length > 3 || typed.any { it !in '0'..'9' }) {
                revertAllChanges()
            } else if (typed != originalText.toString()) {
                settle()
            }
        }
    }

    fun pickType(value: String) {
        if (value == type) return
        type = value
        settle()
    }

    fun pickDay(value: Int) {
        if (value == day) return
        day = value
        settle()
    }

    fun pickDate(iso: String) {
        if (iso == date) return
        date = iso
        settle()
    }

    /** A time from the clock, already "04:00". */
    fun pickTime(value: String) {
        if (value == time) return
        time = value
        settle()
    }

    /** No time: the end of the day. */
    fun clearTime() {
        if (time == null) return
        time = null
        settle()
    }

    /** One more day, or one fewer, never under one. */
    fun step(delta: Int) {
        val n = interval.text.toString().toIntOrNull() ?: 0
        interval.setTextAndPlaceCursorAtEnd((n + delta).coerceIn(1, 999).toString())
        settle()
    }

    fun togglePriority() {
        priority = !priority
        settle()
    }

    fun toggleUrgent() {
        urgent = !urgent
        settle()
    }

    fun toggleWake() {
        wake = !wake
        settle()
    }

    /**
     * A complaint about the form as it was is not about the form as it is now.
     * Except why it cannot be saved at all, which stays true.
     */
    private fun settle() {
        if (!blocked) status = null
    }

    // ── saving and deleting ─────────────────────────────────────────────────

    private val weekly: Boolean
        get() = type == "weekly" || type == "biweekly"

    /** The form as the shared layer wants it: strings, nothing coerced yet. */
    private fun draft(): TaskDraft = TaskDraft(
        name = name.text.toString().trim(),
        // Not "personal". The desktop defaults a new task to the game category
        // and this form has no chooser, so picking anything else here would
        // mean the same button produces a differently-filed task depending on
        // which device it was pressed on. An edit never sends it: see edit().
        category = "game",
        resetType = type,
        resetTime = time,
        resetDay = if (weekly) day.toString() else null,
        resetIntervalDays = if (type == "custom_days") interval.text.toString().trim() else null,
        specificDate = if (type == "specific_date") date else null,
        isPriority = priority,
        isUrgent = urgent,
        ringInQuiet = wake,
    )

    /**
     * The edit, as the columns this form shows and nothing else. A column it
     * leaves out is not touched, which is what stops editing a task on a phone
     * from clearing a field a newer desktop put there.
     */
    private fun edit(): Map<String, String?> {
        val d = draft()
        val mine = mapOf(
            "name" to d.name,
            "reset_type" to d.resetType,
            "reset_time" to d.resetTime,
            "reset_day" to d.resetDay,
            "reset_interval_days" to d.resetIntervalDays,
            "specific_date" to d.specificDate,
            "is_priority" to if (d.isPriority) "1" else "0",
            "is_urgent" to if (d.isUrgent) "1" else "0",
            "ring_in_quiet" to if (d.ringInQuiet) "1" else "0",
        )
        // Every key here is in TASK_EDITABLE, and this is where that stops
        // being a thing to remember. A typo above becomes a column that is
        // silently never written, which is the quietest kind of broken.
        for (k in mine.keys) require(k in TASK_EDITABLE) { "not an editable column: $k" }
        return mine
    }

    fun save() {
        if (leaving || busy || blocked || !loaded) return
        val d = draft()

        // Asked here as well as inside createTask, so the form can say what is
        // wrong without a round trip. The repository asks again because it is
        // what actually writes, and a check that only exists in front of a
        // screen is a check the next caller skips.
        val problems = taskProblems(d)
        if (problems.isNotEmpty()) {
            status = warn(problems.joinToString("\n") { sentence(it) })
            return
        }

        busy = true
        saving = true
        scope.launch {
            try {
                val repo = Repo.open(ctx)
                val id = uid
                if (id == null) {
                    val refused = repo.createTask(d)
                    if (refused.isNotEmpty()) {
                        status = warn(refused.joinToString("\n") { sentence(it) })
                        return@launch
                    }
                } else if (!repo.updateTask(id, edit())) {
                    status = warn(GONE)
                    return@launch
                }
                // Before leaving, not after. The queue this phone hands the OS
                // is rebuilt from the database, and a task saved without it
                // would sit there correct and silent until something else
                // happened to trigger a rebuild.
                Scheduler.reschedule(ctx)
                leave((if (id == null) ADDED else EDITED) + " · " + d.name)
            } catch (e: Exception) {
                status = warn("บันทึกไม่ได้ ${e.message ?: e.toString()}")
            } finally {
                if (!leaving) {
                    busy = false
                    saving = false
                }
            }
        }
    }

    /**
     * Into the bin on this device, and on the other one after the next sync.
     *
     * Not stopped by [blocked]: a task of a kind this form cannot edit is still
     * a task it can throw away, and so is one whose form failed to load.
     */
    fun delete() {
        val id = uid ?: return
        if (leaving || busy) return
        busy = true
        scope.launch {
            try {
                if (!Repo.open(ctx).deleteTask(id)) {
                    status = warn(GONE)
                    return@launch
                }
                // Same reason as after saving: an alarm for a task that is no
                // longer on the list would still ring.
                Scheduler.reschedule(ctx)
                leave(DELETED + " · " + name.text.toString().trim())
            } catch (e: Exception) {
                status = warn("ลบไม่ได้ ${e.message ?: e.toString()}")
            } finally {
                if (!leaving) busy = false
            }
        }
    }

    private fun leave(said: String?) {
        leaving = true
        done(said)
    }

    /**
     * A code turned into a sentence, here and nowhere else. taskProblems
     * returns codes because two languages and a vector file have to agree on
     * the list; this is the one place that decides how they read.
     */
    private fun sentence(code: String): String = when (code) {
        "name-empty" -> "ยังไม่ได้ตั้งชื่องาน"
        "reset-type-unknown" -> "ยังไม่ได้เลือกว่ากลับมาเมื่อไหร่"
        // A time comes off the clock now, so this can only be one that
        // arrived from somewhere else.
        "time-malformed" -> "เวลาไม่ถูกต้อง เลือกเวลาใหม่"
        "weekly-needs-day" -> "งานรายสัปดาห์ต้องบอกว่าวันไหน"
        "day-out-of-range" -> "วันที่เลือกไม่ถูกต้อง"
        "custom-needs-interval" -> "ต้องบอกว่าทำซ้ำทุกกี่วัน"
        "interval-out-of-range" -> "จำนวนวันต้องเป็นเลขจำนวนเต็มตั้งแต่ 1 ขึ้นไป"
        "event-needs-window" -> "ช่วงเวลาต้องมีทั้งวันเริ่มและวันจบ"
        "date-missing" -> "ยังไม่ได้เลือกวันที่"
        else -> code
    }

    private fun warn(text: String) = FormStatus(text, StatusTone.WARN)

    companion object {
        /**
         * The five kinds this form can make, not the seven the engine knows.
         *
         * event_window needs two datetimes and one_time needs an end, and both
         * are the kind of thing picked on a wide screen with a calendar next
         * to it. Leaving them out is an answer to "what would somebody set up
         * standing up", not a limitation waiting to be fixed.
         */
        val TYPES = listOf(
            "daily" to "ทุกวัน",
            "weekly" to "ทุกสัปดาห์",
            "biweekly" to "ทุกสองสัปดาห์",
            "custom_days" to "ทุก N วัน",
            "specific_date" to "วันที่กำหนด",
        )

        private const val ADDED = "เพิ่มงานแล้ว"
        private const val EDITED = "แก้งานแล้ว"
        private const val DELETED = "ลบงานแล้ว"
        private const val GONE = "ไม่พบงานนี้แล้ว อาจถูกลบไปจากอีกเครื่อง"
        private const val UNSUPPORTED = "งานนี้เป็นชนิดที่หน้านี้ยังแก้ไม่ได้ แก้บนคอมแทน แต่ลบได้"
    }
}
