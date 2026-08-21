package app.reup

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.reup.sync.TASK_EDITABLE
import app.reup.sync.TaskDraft
import app.reup.sync.taskProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The other direction.
 *
 * Until now everything in the database on this phone arrived by sync. A task
 * thought of on a bus went into a note somewhere and got typed in later at the
 * desk, or did not. This is the screen that closes that.
 *
 * WHY THIS FILE IS AS THIN AS IT IS
 * ---------------------------------
 * Same reason SyncActivity and AndroidDb are. This module is the one place no
 * test in this project can reach, so what could be wrong has been moved out:
 * which columns a task has, how each value is read, and what makes a draft
 * unschedulable all live in TaskDraft.kt, against a vector file the desktop
 * generates. What is left here is reading a form, calling one function, and
 * turning a list of codes into sentences.
 *
 * WHY IT REFUSES WHERE THE DESKTOP FORM DOES NOT
 * ----------------------------------------------
 * The desktop form defaults its way past most of what taskProblems can find,
 * and it can, because every control on it has a default — a dropdown is never
 * empty and a date picker starts on today. This screen is text boxes, and a
 * text box has nothing to fall back on. A weekly task saved with no day is a
 * row that sits in the list looking completely normal and never rings once.
 *
 * WHY EDITING IS THIS SCREEN AND NOT ANOTHER ONE
 * ----------------------------------------------
 * An edit form is an add form with the boxes already filled in. Writing it as a
 * second activity would mean two copies of every field, every chip, every
 * show-and-hide rule and every sentence — on the one module where nothing can
 * be tested, which is the worst possible place to keep two of something.
 *
 * So there is one screen and one extra: with a uid it loads a row and updates
 * it, without one it creates. The only things that branch are the title, the
 * button and which repository call happens at the end.
 *
 * WHY THE TIME IS A TEXT BOX AND NOT A CLOCK
 * ------------------------------------------
 * The system time picker returns an hour and a minute in the phone's locale and
 * hands back no string; every path from it to "04:00" is code that has to agree
 * with what the desktop writes, on a screen no test can see. A box that accepts
 * exactly four digits and a colon is checked by the same function on both sides.
 */
class AddTaskActivity : Activity() {

    private lateinit var nameBox: EditText
    private lateinit var timeBox: EditText
    private lateinit var intervalBox: EditText
    private lateinit var dateBox: EditText
    private lateinit var intervalRow: LinearLayout
    private lateinit var dateRow: LinearLayout
    private lateinit var dayRow: LinearLayout
    private lateinit var typeRow: LinearLayout
    private lateinit var flagRow: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var status: TextView

    private val typeChips = mutableListOf<Button>()
    private val dayChips = mutableListOf<Button>()
    private val flagChips = mutableListOf<Button>()

    private var type = "daily"
    private var day = 1
    private var priority = false
    private var urgent = false
    private var busy = false

    /**
     * Set when there is nothing worth saving: the row is gone, or it is a kind
     * of task this screen cannot represent.
     *
     * A flag rather than reading the button back, because [render] runs after
     * every change and would otherwise re-enable whatever the last failure had
     * just switched off.
     */
    private var blocked = false

    /**
     * Whether the delete button has been pressed once already.
     *
     * Two presses rather than a dialog, and rather than one. One press is a
     * task off the list from a mis-tap on a phone in one hand; a dialog is a
     * second window on a screen that has never opened one. Touching anything
     * else clears it, so the armed state cannot survive being forgotten about.
     */
    private var armed = false

    /** Null means this is a new task. Anything else is the row being edited. */
    private var uid: String? = null

    /**
     * What was in the row when it was opened.
     *
     * Kept so that the update sends the columns this screen knows about and
     * nothing else. A row that arrived from a desktop running a newer build has
     * columns this version cannot show; sending a blank for them because the
     * form has no box would quietly erase them.
     */
    private var loaded: Map<String, String?> = emptyMap()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uid = intent.getStringExtra(EXTRA_UID)
        title = if (uid == null) "เพิ่มงาน" else "แก้ไขงาน"

        nameBox = field("ชื่องาน", InputType.TYPE_CLASS_TEXT)
        timeBox = field("เวลา เช่น 04:00", InputType.TYPE_CLASS_TEXT)
        intervalBox = field("ทุกกี่วัน", InputType.TYPE_CLASS_NUMBER)
        dateBox = field("วันที่ เช่น 2026-09-02", InputType.TYPE_CLASS_TEXT)

        typeRow = LinearLayout(this)
        typeRow.orientation = LinearLayout.VERTICAL
        for ((value, text) in TYPES) {
            val b = Button(this)
            b.text = text
            b.setOnClickListener { pickType(value) }
            b.tag = value
            typeChips.add(b)
            typeRow.addView(b, rowParams())
        }

        dayRow = LinearLayout(this)
        dayRow.orientation = LinearLayout.HORIZONTAL
        for (i in DAYS.indices) {
            val b = Button(this)
            b.text = DAYS[i]
            b.setOnClickListener { pickDay(i) }
            dayChips.add(b)
            dayRow.addView(b, chipParams())
        }

        intervalRow = LinearLayout(this)
        intervalRow.orientation = LinearLayout.VERTICAL
        intervalRow.addView(label("ทำซ้ำทุกกี่วัน"), rowParams())
        intervalRow.addView(intervalBox, rowParams())

        dateRow = LinearLayout(this)
        dateRow.orientation = LinearLayout.VERTICAL
        dateRow.addView(label("วันที่"), rowParams())
        dateRow.addView(dateBox, rowParams())

        flagRow = LinearLayout(this)
        flagRow.orientation = LinearLayout.HORIZONTAL
        val star = Button(this)
        star.setOnClickListener { priority = !priority; armed = false; render() }
        val fire = Button(this)
        fire.setOnClickListener { urgent = !urgent; armed = false; render() }
        flagChips.add(star)
        flagChips.add(fire)
        flagRow.addView(star, chipParams())
        flagRow.addView(fire, chipParams())

        saveButton = Button(this)
        saveButton.text = "บันทึก"
        saveButton.setOnClickListener { save() }

        deleteButton = Button(this)
        deleteButton.setOnClickListener { remove() }

        status = TextView(this)
        status.setTextColor(Color.parseColor("#E8E8EA"))
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.typeface = Typeface.MONOSPACE

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(48, 48, 48, 64)
        column.addView(nameBox, rowParams())
        column.addView(label("กลับมาเมื่อไหร่"), rowParams())
        column.addView(typeRow, rowParams())
        column.addView(label("เวลา"), rowParams())
        column.addView(note(TIME_NOTE), rowParams())
        column.addView(timeBox, rowParams())
        column.addView(label("วันไหน"), rowParams())
        column.addView(dayRow, rowParams())
        column.addView(intervalRow, rowParams())
        column.addView(dateRow, rowParams())
        column.addView(label("ทำเครื่องหมาย"), rowParams())
        column.addView(flagRow, rowParams())
        column.addView(saveButton, rowParams())
        // Only when there is something to delete, and below the save button:
        // the thing being reached for on this screen is almost always save.
        if (uid != null) column.addView(deleteButton, rowParams())
        column.addView(status, rowParams())

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0F0F12"))
        scroll.addView(column)
        setContentView(scroll)

        render()
        uid?.let { load(it) }
    }

    /**
     * Fill the boxes from the row.
     *
     * A missing row is a real answer rather than an error on a device that
     * syncs: the task may have been deleted on the desktop between this list
     * being drawn and this row being opened. Saying so and closing beats an
     * empty form that silently turns into a new task.
     */
    private fun load(id: String) {
        busy = true
        render()
        scope.launch {
            try {
                val fields = Repo.open(this@AddTaskActivity).taskForEdit(id)
                if (fields == null) {
                    status.text = GONE
                    blocked = true
                    return@launch
                }
                loaded = fields
                nameBox.setText(fields["name"] ?: "")
                timeBox.setText(fields["reset_time"] ?: "")
                intervalBox.setText(fields["reset_interval_days"] ?: "")
                dateBox.setText(fields["specific_date"] ?: "")
                type = fields["reset_type"] ?: "daily"
                day = fields["reset_day"]?.toIntOrNull() ?: 1
                priority = fields["is_priority"] == "1"
                urgent = fields["is_urgent"] == "1"
                // A task made on the desktop can be a kind this screen has no
                // chip for. Showing it as something else would be a lie that
                // saving would then make true.
                if (TYPES.none { it.first == type }) {
                    status.text = UNSUPPORTED
                    blocked = true
                }
            } catch (e: Exception) {
                status.text = "เปิดงานนี้ไม่ได้ ${e.message ?: e.toString()}"
                blocked = true
            } finally {
                busy = false
                render()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pickType(value: String) {
        type = value
        status.text = ""
        armed = false
        render()
    }

    private fun pickDay(value: Int) {
        day = value
        armed = false
        render()
    }

    /**
     * Everything that changes on screen, in one place, called after every
     * change.
     *
     * Rows are hidden rather than disabled: a field that does not apply to the
     * chosen kind of task is not a field that is temporarily unavailable, it is
     * one that has no meaning, and leaving it visible-but-grey invites filling
     * it in and wondering why nothing happened.
     */
    private fun render() {
        for (b in typeChips) {
            val mine = b.tag == type
            b.alpha = if (mine) 1f else 0.55f
            b.typeface = if (mine) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        for (i in dayChips.indices) {
            dayChips[i].alpha = if (i == day) 1f else 0.55f
            dayChips[i].typeface = if (i == day) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        flagChips[0].text = if (priority) "★ สำคัญ" else "☆ สำคัญ"
        flagChips[1].text = if (urgent) "🔥 ด่วน" else "ด่วน"

        val weekly = type == "weekly" || type == "biweekly"
        dayRow.visibility = if (weekly) LinearLayout.VISIBLE else LinearLayout.GONE
        intervalRow.visibility = if (type == "custom_days") LinearLayout.VISIBLE else LinearLayout.GONE
        dateRow.visibility = if (type == "specific_date") LinearLayout.VISIBLE else LinearLayout.GONE

        saveButton.isEnabled = !busy && !blocked
        deleteButton.isEnabled = !busy
        deleteButton.text = if (armed) "แน่ใจนะ กดอีกครั้งเพื่อลบ" else "ลบงานนี้"
        saveButton.text = when {
            busy -> "กำลังบันทึก"
            uid == null -> "บันทึก"
            else -> "บันทึกการแก้ไข"
        }
    }

    /** The form as the shared layer wants it: strings, nothing coerced yet. */
    private fun draft(): TaskDraft = TaskDraft(
        name = nameBox.text.toString().trim(),
        // Not "personal". The desktop defaults a new task to the game category
        // and this screen has no chooser, so picking anything else here would
        // mean the same button produces a differently-filed task depending on
        // which device it was pressed on.
        category = "game",
        resetType = type,
        resetTime = timeBox.text.toString().trim(),
        resetDay = if (type == "weekly" || type == "biweekly") day.toString() else null,
        resetIntervalDays = if (type == "custom_days") intervalBox.text.toString().trim() else null,
        specificDate = if (type == "specific_date") dateBox.text.toString().trim() else null,
        isPriority = priority,
        isUrgent = urgent,
    )

    /**
     * The edit, as the columns this screen actually shows.
     *
     * Built from what was loaded rather than from the whole allowlist: a column
     * that was not in the row stays out of the update, and [taskUpdate] leaves
     * out what it is not given. That is what stops editing a task on a phone
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
        )
        // Every key here is in TASK_EDITABLE, and this is where that stops
        // being a thing to remember. A typo above becomes a column that is
        // silently never written, which is the quietest kind of broken.
        for (k in mine.keys) require(k in TASK_EDITABLE) { "not an editable column: $k" }
        return mine
    }

    /**
     * Into the bin on this device, and on the other one after the next sync.
     *
     * Not blocked by [blocked]: a task of a kind this screen cannot edit is
     * still a task it can throw away, and so is one whose form failed to load.
     * Refusing to delete something because its fields could not be shown would
     * leave rows on the list with no way to remove them from here at all.
     */
    private fun remove() {
        val id = uid ?: return
        if (busy) return
        if (!armed) {
            armed = true
            status.text = ""
            render()
            return
        }

        busy = true
        armed = false
        render()
        scope.launch {
            try {
                val gone = Repo.open(this@AddTaskActivity).deleteTask(id)
                if (!gone) {
                    status.text = GONE
                    return@launch
                }
                // Same reason as after saving: the queue handed to the OS is
                // built from the database, and an alarm for a task that is no
                // longer on the list would still ring.
                Scheduler.reschedule(this@AddTaskActivity)
                finish()
            } catch (e: Exception) {
                status.text = "ลบไม่ได้ ${e.message ?: e.toString()}"
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun save() {
        if (busy) return
        armed = false
        val d = draft()

        // Asked here as well as inside createTask, so that the screen can say
        // what is wrong without a round trip to the database. The repository
        // asks again because it is what actually writes, and a check that only
        // exists in front of a screen is a check that the next caller skips.
        val problems = taskProblems(d)
        if (problems.isNotEmpty()) {
            status.text = problems.joinToString("\n") { sentence(it) }
            return
        }

        busy = true
        render()
        scope.launch {
            try {
                val repo = Repo.open(this@AddTaskActivity)
                val id = uid
                if (id == null) {
                    val refused = repo.createTask(d)
                    if (refused.isNotEmpty()) {
                        status.text = refused.joinToString("\n") { sentence(it) }
                        return@launch
                    }
                } else if (!repo.updateTask(id, edit())) {
                    status.text = GONE
                    return@launch
                }
                // Before finishing, not after. The queue this phone hands the OS
                // is rebuilt from the database, and a task saved without it
                // would sit there correct and silent until something else
                // happened to trigger a rebuild.
                Scheduler.reschedule(this@AddTaskActivity)
                finish()
            } catch (e: Exception) {
                status.text = "บันทึกไม่ได้ ${e.message ?: e.toString()}"
            } finally {
                busy = false
                render()
            }
        }
    }

    /**
     * A code turned into a sentence, here and nowhere else.
     *
     * taskProblems returns codes rather than words because two languages and a
     * vector file have to agree on the list. This is the one place that decides
     * how they read, which is also the only place that knows the screen they
     * are read on.
     */
    private fun sentence(code: String): String = when (code) {
        "name-empty" -> "ยังไม่ได้ตั้งชื่องาน"
        "reset-type-unknown" -> "ยังไม่ได้เลือกว่ากลับมาเมื่อไหร่"
        "time-malformed" -> "เวลาต้องเป็นแบบ 04:00 สองหลักทั้งชั่วโมงและนาที"
        "weekly-needs-day" -> "งานรายสัปดาห์ต้องบอกว่าวันไหน"
        "day-out-of-range" -> "วันที่เลือกไม่ถูกต้อง"
        "custom-needs-interval" -> "ต้องบอกว่าทำซ้ำทุกกี่วัน"
        "interval-out-of-range" -> "จำนวนวันต้องเป็นเลขจำนวนเต็มตั้งแต่ 1 ขึ้นไป"
        "event-needs-window" -> "ช่วงเวลาต้องมีทั้งวันเริ่มและวันจบ"
        "date-missing" -> "ต้องใส่วันที่"
        else -> code
    }

    private fun field(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = InputType.TYPE_CLASS_TEXT or type
        e.setTextColor(Color.parseColor("#E8E8EA"))
        e.setHintTextColor(Color.parseColor("#6B6B72"))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        return e
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor("#E8E8EA"))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor("#8A8A92"))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        return t
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        p.topMargin = 24
        return p
    }

    private fun chipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        /**
         * The five kinds this screen can make, not the seven the engine knows.
         *
         * `event_window` needs two datetimes and `one_time` needs an end, and
         * both are the kind of thing that is picked on a wide screen with a
         * calendar next to it. Leaving them out is not a limitation to be fixed
         * later so much as an answer to "what would somebody actually type in
         * standing up".
         */
        private val TYPES = listOf(
            "daily" to "ทุกวัน",
            "weekly" to "ทุกสัปดาห์",
            "biweekly" to "ทุกสองสัปดาห์",
            "custom_days" to "ทุก N วัน",
            "specific_date" to "วันที่กำหนด",
        )

        /** Sunday first, because reset_day counts Sunday as zero. */
        private val DAYS = listOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")

        private const val TIME_NOTE =
            "ว่างไว้ได้ ถ้าไม่ใส่จะถือว่าสิ้นวัน แบบเดียวกับบนคอม"

        private const val GONE =
            "ไม่พบงานนี้แล้ว อาจถูกลบไปจากอีกเครื่อง"

        private const val UNSUPPORTED =
            "งานนี้เป็นชนิดที่หน้าจอนี้ยังแก้ไม่ได้ แก้บนคอมแทน แต่ลบได้"

        /** Which row to edit. Absent means make a new one. */
        const val EXTRA_UID = "app.reup.uid"
    }
}