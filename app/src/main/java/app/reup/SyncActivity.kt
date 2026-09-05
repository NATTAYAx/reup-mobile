package app.reup

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.reup.sync.BackendChoice
import app.reup.sync.SetupProblem
import app.reup.sync.SetupResult
import app.reup.sync.StorageErrorKind
import app.reup.sync.StorageException
import app.reup.sync.SyncConfig
import app.reup.sync.SYNC_OFF
import app.reup.sync.SyncConfigs
import app.reup.sync.SyncFields
import app.reup.sync.SyncValue
import app.reup.sync.driveTokenSource
import app.reup.sync.SyncSetup
import app.reup.sync.SyncSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The screen that was missing.
 *
 * Everything under `app.reup.sync` has existed and been tested for weeks, and
 * none of it could be reached, because nothing on this device could hand it a
 * folder and a key. This is that.
 *
 * WHY THIS FILE IS AS THIN AS IT IS
 * ---------------------------------
 * Same reason AndroidDb.kt is two methods long. This module is the one place no
 * test in this project can reach, so anything in it that could be wrong has
 * been moved out: the rules about what a half-filled form means live in
 * SyncSetup, and the assembly of store, storage, cipher and key lives in
 * Config. What is left here is reading a form, calling one function, and
 * turning the answer into a sentence.
 *
 * WHY THE BACKEND IS A PAIR OF BUTTONS AND NOT A GUESS
 * ----------------------------------------------------
 * It used to be inferred: text in the address box meant WebDAV, an empty box
 * meant off. Drive had no way in at all, so signing in to Google stored a token
 * and changed nothing — every sync afterwards still went to the WebDAV folder
 * and reported success. Nothing errored, which is what made it expensive.
 *
 * A choice that the rest of the system can hold has to be a thing a person can
 * make. So it is two buttons, it is part of SyncFields, and pressing one makes
 * the sync button ask to be saved first, because a target that is on screen but
 * not on disk is the bug this replaced.
 *
 * WHY THERE IS NO TIMER
 * ---------------------
 * The engine is safe to call again immediately and safe to interrupt anywhere,
 * so a fifteen-minute interval would be correct. It is still not here, for the
 * reason the desktop card gives: a run that happens on its own turns "the phone
 * has an old copy" into a question with two answers — the sync did not work, or
 * it has not gone yet — with no way to tell them apart from the screen. The
 * button comes first. The timer goes in once both devices are known to agree.
 *
 * WHY THE TASK COUNT IS ON THIS SCREEN
 * ------------------------------------
 * It is the only thing here that is evidence rather than a claim. The status
 * line says what the sync reported; the count says what is actually in the
 * database on this phone afterwards.
 */
class SyncActivity : Activity() {

    private lateinit var webdavLabel: TextView
    private lateinit var urlBox: EditText
    private lateinit var userBox: EditText
    private lateinit var passBox: EditText
    private lateinit var codeBox: EditText
    private lateinit var syncButton: Button
    private lateinit var driveButton: Button
    private lateinit var saveButton: Button

    /**
     * Proof that a long sync is working rather than stuck.
     *
     * The first sync after a fresh install pulls the whole folder — the
     * snapshot plus every batch written since — so minutes is a normal answer
     * and not a broken one. The screen said "กำลังซิงก์" and then nothing at
     * all, which is indistinguishable from a hang, and the only sensible thing
     * to do about a hang is press the button again.
     */
    private val ticker = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private lateinit var webdavChip: Button
    private lateinit var driveChip: Button
    private lateinit var status: TextView

    /**
     * Main-thread scope, cancelled with the screen.
     *
     * Nothing here needs a background thread of its own: AndroidDb already puts
     * every statement on Dispatchers.IO, and the HTTP transport does the same.
     * Work launched from a button and cancelled in onDestroy is the whole of the
     * concurrency in this app — with one exception, the token exchange, which
     * happens in OAuthRedirectActivity and is explicitly *not* owned by a
     * screen. That file says why at length.
     */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var config: SyncConfig = SYNC_OFF
    private var choice: BackendChoice = BackendChoice.WEBDAV
    private var taskCount: Int? = null
    private var line: String = "กำลังเปิดฐานข้อมูล"
    private var summary: SyncSummary? = null
    private var signInNote: String? = null
    private var busy = false
    private var driveOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before any view exists: everything below reads its colours out of
        // Ui, and a screen that repaints into a different theme a moment
        // after opening is worse than one with no choice at all.
        Ui.load(this)

        title = "ซิงก์กับคอม"

        val driveAvailable = AndroidSignIn.clientId() != null

        webdavLabel = label("โฟลเดอร์ WebDAV")
        urlBox = field("โฟลเดอร์ WebDAV", InputType.TYPE_TEXT_VARIATION_URI)
        userBox = field("ชื่อผู้ใช้", InputType.TYPE_CLASS_TEXT)
        passBox = field("รหัสผ่าน", InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        codeBox = field("รหัสจับคู่ จากหน้าตั้งค่าบนคอม", InputType.TYPE_CLASS_TEXT)
        codeBox.setHorizontallyScrolling(false)
        codeBox.maxLines = 3

        webdavChip = Ui.chip(this, "เซิร์ฟเวอร์ในบ้าน") { pick(BackendChoice.WEBDAV) }
        driveChip = Ui.chip(this, "Google Drive") { pick(BackendChoice.DRIVE) }

        val chips = Ui.strip(this)
        chips.addView(webdavChip, Ui.cell(this, true))
        chips.addView(driveChip, Ui.cell(this))

        saveButton = Ui.secondary(this, "บันทึกการตั้งค่า") { save() }
        driveButton = Ui.secondary(this, "เชื่อม Google Drive") { drive() }
        syncButton = Ui.primary(this, "ซิงก์ตอนนี้") { run() }

        // Kept monospace, unlike the forms: this one is a readout of what a
        // folder and a server said, read as columns.
        status = Ui.mono(this)
        status.setTextIsSelectable(true)

        val screen = Ui.sticky(this)
        val column = screen.column
        column.addView(
            Ui.header(this, "ซิงก์กับคอม", "โฟลเดอร์ รหัสจับคู่ และการดึงข้อมูล"),
            Ui.row(this, 0f),
        )

        // The chooser only exists when there is something to choose. Anyone who
        // cloned the repository has no local.properties and therefore no client
        // id, and a button that cannot work is worse than one that is not there.
        if (driveAvailable) {
            column.addView(label("เก็บไฟล์ไว้ที่ไหน"), rowParams())
            column.addView(chips, rowParams())
            column.addView(note(DRIVE_NOTE), rowParams())
        }

        column.addView(webdavLabel, rowParams())
        column.addView(urlBox, rowParams())
        column.addView(userBox, rowParams())
        column.addView(passBox, rowParams())
        column.addView(label("รหัสจับคู่"), rowParams())
        column.addView(note(CODE_NOTE), rowParams())
        column.addView(codeBox, rowParams())
        if (driveAvailable) {
            column.addView(driveButton, Ui.row(this, 24f))
        }
        column.addView(status, rowParams())

        // Saving and syncing are the two verbs of this screen, so they are
        // where a thumb is rather than at the end of a form.
        screen.bar.addView(saveButton, Ui.cell(this, true))
        screen.bar.addView(syncButton, Ui.cell(this))
        setContentView(screen.root)

        render()
        load()
    }

    /**
     * The sign-in finishes in OAuthRedirectActivity, which is a different
     * activity and now outlives its own screen, so this one learns about it by
     * looking again rather than by being told. Two things are read: whether a
     * refresh token is there, and what the redirect recorded. They are separate
     * on purpose — "the button says connected" and "the sign-in reported
     * success" are two claims, and the evening this file was rewritten was
     * spent because there was no way to see them disagree.
     */
    override fun onResume() {
        super.onResume()
        scope.launch {
            val db = Repo.database(this@SyncActivity)
            config = SyncConfigs.load(db)
            driveOn = AndroidSignIn(db, AndroidHttpTransport()).connected()
            signInNote = lastSignIn()
            render()
        }
    }

    /**
     * Counts the seconds out loud while a sync runs.
     *
     * Stops itself the moment [busy] goes false, so there is nothing to cancel
     * on the happy path and no second place that has to remember to.
     */
    private fun beat() {
        if (!busy) return
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        line = if (seconds < 12) {
            "กำลังซิงก์ " + seconds + " วินาที"
        } else {
            // Only after it has already felt long. Saying it up front would be
            // a warning on every sync, including the ones that take two seconds.
            "กำลังซิงก์ " + seconds + " วินาที · ครั้งแรกหลังลงแอปใหม่ต้องดึงของทั้งหมด" +
                    "ลงมา อาจใช้เวลาหลายนาที ปล่อยหน้านี้เปิดไว้ได้"
        }
        render()
        ticker.postDelayed({ beat() }, 1000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    // ─── the four things this screen does ───────────────────────────────────

    private fun pick(b: BackendChoice) {
        if (busy || choice == b) return
        choice = b
        render()
    }

    private fun load() {
        scope.launch {
            try {
                // Repo is the bootstrap now, for every entry point on this
                // side: this screen, the alarm receiver and the boot receiver.
                // It stays out of syncNow because a function that creates
                // tables as a side effect of syncing is a function nobody can
                // reason about when the tables turn out to be wrong.
                val repo = Repo.open(this@SyncActivity)
                config = SyncConfigs.load(Repo.database(this@SyncActivity))
                fill(SyncSetup.fieldsOf(config))
                signInNote = lastSignIn()
                taskCount = repo.tasks().size
                line = if (SyncConfigs.isReady(config)) "พร้อมซิงก์" else "ยังตั้งค่าไม่ครบ"
            } catch (e: Exception) {
                line = "เปิดฐานข้อมูลไม่ได้ " + (e.message ?: e.toString())
            }
            render()
        }
    }

    private fun save() {
        if (busy) return
        when (val result = SyncSetup.apply(config, typed())) {
            is SetupResult.Refused -> {
                // Nothing is written, including the parts that were fine. Saying
                // which half went in and which did not is a screen nobody can
                // read, so neither half goes in.
                line = when (result.problem) {
                    SetupProblem.UNREADABLE_CODE ->
                        "รหัสจับคู่นั้นอ่านไม่ออก ยังไม่บันทึกอะไรให้เลย ของเดิมยังอยู่ครบ"
                    SetupProblem.UNUSABLE_URL ->
                        "ที่อยู่นั้นใช้ไม่ได้ " + (result.detail ?: "")
                }
                render()
            }
            is SetupResult.Accepted -> scope.launch {
                try {
                    val db = Repo.database(this@SyncActivity)
                    // save() compares the old target with the new one and drops
                    // the cursor when they differ, which is exactly what has to
                    // happen when this switches between WebDAV and Drive: the
                    // two are different piles of files and a record of what was
                    // said to one means nothing to the other.
                    SyncConfigs.save(db, result.config)
                    config = result.config
                    // Put the saved values back on the screen rather than
                    // leaving what was typed. If the code box was left empty and
                    // the old code was kept, this is the only thing that says
                    // so — and if the backend moved to Drive, this is what
                    // clears the three boxes that no longer apply.
                    fill(SyncSetup.fieldsOf(config))
                    line = if (SyncConfigs.isReady(config)) "บันทึกแล้ว พร้อมซิงก์" else "บันทึกแล้ว แต่ยังไม่ครบ"
                } catch (e: Exception) {
                    line = "บันทึกไม่ได้ " + (e.message ?: e.toString())
                }
                render()
            }
        }
    }

    private fun drive() {
        if (busy) return
        val id = AndroidSignIn.clientId() ?: return

        scope.launch {
            val db = Repo.database(this@SyncActivity)
            val signIn = AndroidSignIn(db, AndroidHttpTransport())

            if (driveOn) {
                signIn.disconnect()
                driveOn = false
                // The backend setting is left alone. Signing out of Google on
                // this phone and choosing where the files go are two different
                // decisions, and the sync button already says what is missing
                // when the chosen backend has nobody signed in.
                line = "ตัดการเชื่อมต่อแล้ว เฉพาะเครื่องนี้ คอมยังใช้ได้ต่อ"
                render()
                return@launch
            }

            line = "กำลังเปิดเบราว์เซอร์"
            render()
            if (!signIn.start(this@SyncActivity, id)) {
                line = "เปิดเบราว์เซอร์ไม่ได้"
                render()
            }
        }
    }

    private fun run() {
        if (busy) return

        if (typed() != SyncSetup.fieldsOf(config)) {
            // A half-typed address, or a backend picked but not saved, must
            // never be what a sync runs against. The run would otherwise use the
            // saved one and look like it ignored the change.
            line = "กดบันทึกการตั้งค่าก่อน"
            render()
            return
        }
        if (!SyncConfigs.isReady(config)) {
            line = "ต้องมีทั้งปลายทางและรหัสจับคู่ก่อนถึงจะซิงก์ได้"
            render()
            return
        }
        if (choice == BackendChoice.DRIVE && !driveOn) {
            // Caught here rather than left to syncNow's null, which the screen
            // would otherwise report as "ยังตั้งค่าไม่ครบ" — true, and useless.
            line = "เลือก Google Drive ไว้แต่เครื่องนี้ยังไม่ได้ล็อกอิน กดเชื่อมก่อน"
            render()
            return
        }

        busy = true
        summary = null
        startedAt = System.currentTimeMillis()
        beat()

        scope.launch {
            try {
                val db = Repo.database(this@SyncActivity)
                val http = AndroidHttpTransport()
                // Null for WebDAV, and null for Drive when nobody has signed in
                // on this phone — which syncNow reads as "not set up", the same
                // as an empty address box.
                val tokens = driveTokenSource(db, http, AndroidSignIn.clientId()) {
                    System.currentTimeMillis() / 1000
                }
                val report = SyncConfigs.syncNow(db, http, AndroidAeadCipher(), tokens)
                if (report == null) {
                    // Gated above, so this is a bug rather than a state worth
                    // wording.
                    line = "ยังตั้งค่าไม่ครบ"
                } else {
                    summary = SyncSetup.summarise(report)
                    taskCount = Repo.open(this@SyncActivity).tasks().size
                    line = "ซิงก์เสร็จ"
                }
            } catch (e: StorageException) {
                // The adapters already separated "your password is wrong" from
                // "the server is busy", so this is a lookup rather than a guess.
                line = when (e.kind) {
                    StorageErrorKind.CONFIG -> "ที่อยู่นั้นใช้ไม่ได้ ดูว่าขึ้นต้นด้วย https และชี้ไปที่โฟลเดอร์รึเปล่า"
                    StorageErrorKind.AUTH -> "เซิร์ฟเวอร์ไม่รับชื่อผู้ใช้หรือรหัสผ่านนั้น"
                    StorageErrorKind.NOT_FOUND -> "เซิร์ฟเวอร์ไม่มีโฟลเดอร์ที่อยู่นั้น"
                    StorageErrorKind.NETWORK -> "ต่อไปหาเซิร์ฟเวอร์ไม่ติด"
                    StorageErrorKind.SERVER -> "เซิร์ฟเวอร์มีปัญหา ของฝั่งนี้ไม่ได้หายไปไหน เดี๋ยวลองใหม่"
                }
            } catch (e: Exception) {
                // Kept as it came. An unexpected failure rewritten into a
                // friendly sentence is an unexpected failure nobody can report.
                line = e.message ?: e.toString()
            }
            busy = false
            ticker.removeCallbacksAndMessages(null)
            render()
        }
    }

    // ─── plumbing ───────────────────────────────────────────────────────────

    private suspend fun lastSignIn(): String? {
        return try {
            val rows = Repo.database(this@SyncActivity).select(
                "SELECT value FROM app_settings WHERE key = ?",
                listOf(SyncValue.Text(SIGN_IN_NOTE_KEY)),
            )
            (rows.firstOrNull()?.get("value") as? SyncValue.Text)?.value
        } catch (e: Exception) {
            null
        }
    }

    private fun typed(): SyncFields = SyncFields(
        baseUrl = urlBox.text.toString(),
        username = userBox.text.toString(),
        password = passBox.text.toString(),
        pairing = codeBox.text.toString(),
        backend = choice,
    )

    private fun fill(f: SyncFields) {
        urlBox.setText(f.baseUrl)
        userBox.setText(f.username)
        passBox.setText(f.password)
        codeBox.setText(f.pairing)
        choice = f.backend
    }

    /**
     * The string is built into locals first, deliberately.
     *
     * MainActivity says why at length: an earlier version of that screen nested
     * literals and lambdas inside templates, and one mangled character turned
     * into an unterminated string that swallowed the rest of the file and put
     * the compiler's complaint two hundred lines from the mistake.
     */
    private fun render() {
        val webdav = choice == BackendChoice.WEBDAV

        if (::webdavChip.isInitialized) {
            Ui.select(this, webdavChip, webdav)
            Ui.select(this, driveChip, !webdav)
        }

        // Three boxes that do nothing is a screen that says something untrue.
        val boxes = if (webdav) View.VISIBLE else View.GONE
        if (::urlBox.isInitialized) {
            webdavLabel.visibility = boxes
            urlBox.visibility = boxes
            userBox.visibility = boxes
            passBox.visibility = boxes
        }

        if (::driveButton.isInitialized) {
            driveButton.text = if (driveOn) "ตัดการเชื่อมต่อ Google Drive" else "เชื่อม Google Drive"
        }

        val sb = StringBuilder()

        val count = taskCount
        sb.append("งานในฐานข้อมูลบนเครื่องนี้  ")
        sb.append(if (count == null) "ยังไม่ได้อ่าน" else count.toString())
        sb.append("\n\n")

        sb.append("ปลายทางที่บันทึกไว้  ")
        sb.append(savedTargetText()).append("\n\n")

        sb.append(line).append("\n")

        val s = summary
        if (s != null) {
            sb.append("\n")
            if (s.quiet) {
                sb.append("ไม่มีอะไรขยับสองทาง\n")
            }
            sb.append("รับเข้ามา ").append(s.applied)
            sb.append("  ส่งออกไป ").append(s.pushed)
            sb.append("  อ่านไป ").append(s.read).append(" ก้อน\n")
            if (s.skipped > 0) {
                sb.append("มี ").append(s.skipped)
                sb.append(" ไฟล์ที่อ่านไม่ได้ ปล่อยไว้แล้วลองใหม่รอบหน้า\n")
            }
        }

        val n = signInNote
        if (n != null) {
            sb.append("\nล็อกอิน Google ครั้งล่าสุด\n").append(n).append("\n")
        }

        status.text = sb.toString()
        syncButton.isEnabled = !busy
        // These two were left pressable while a sync ran. Every entry point
        // starts with `if (busy) return`, so pressing them was already
        // harmless — and that is the problem: a button that looks pressable and
        // does nothing at all reads as a frozen screen, which is exactly what
        // makes somebody press it again.
        if (::saveButton.isInitialized) saveButton.isEnabled = !busy
        if (::driveButton.isInitialized) driveButton.isEnabled = !busy
    }

    /**
     * What is on disk, not what is on screen.
     *
     * The two differ for exactly as long as it takes to press save, and that
     * gap is where the whole Drive problem lived: a backend selected in memory
     * while every sync loaded something else from the database.
     */
    private fun savedTargetText(): String = when (val b = config.backend) {
        app.reup.sync.SyncBackend.Off -> "ยังไม่ได้ตั้ง"
        is app.reup.sync.SyncBackend.WebDav -> "เซิร์ฟเวอร์ในบ้าน " + b.baseUrl
        app.reup.sync.SyncBackend.Drive -> "Google Drive"
    }

    // ─── looks ──────────────────────────────────────────────────────────────
    //
    // These four were a private copy of a design, in every screen, in raw
    // pixels and hand-picked greys. What is left of them is the one decision
    // that really is local: which keyboard this particular box wants.

    private fun field(hint: String, type: Int): EditText =
        Ui.field(this, hint, InputType.TYPE_CLASS_TEXT or type)

    private fun label(text: String): TextView = Ui.fieldLabel(this, text)

    private fun note(text: String): TextView = Ui.note(this, text)

    // topMargin was 24 PIXELS, which on this phone is about nine points and on
    // a cheap one is twenty-four. Same code, two layouts, neither chosen.
    private fun rowParams(): LinearLayout.LayoutParams = Ui.row(this, 14f)

    private fun chipParams(): LinearLayout.LayoutParams = Ui.cell(this)

    private companion object {
        /**
         * Drive, in two lines, where the choice is made.
         *
         * The second line is the one that matters: it is the difference between
         * the two backends, and it is not obvious from their names.
         */
        const val DRIVE_NOTE =
            "โฟลเดอร์ที่ซ่อนไว้ใน Google Drive ของเธอ ที่มีแต่แอปนี้เห็น\n" +
                    "ต่างจากเซิร์ฟเวอร์ในบ้านตรงที่อันนี้เข้าถึงได้ตอนคอมปิดและตอนออกนอกบ้าน"

        /**
         * The phone cannot make a code, and that is the design rather than a
         * gap. One place can create a key, and it is the one place with room to
         * say plainly what losing it costs.
         */
        const val CODE_NOTE =
            "สร้างบนคอมแล้วเอามาวางที่นี่ เครื่องนี้สร้างเองไม่ได้\n" +
                    "ถ้าไม่แก้ ปล่อยช่องนี้ว่างไว้ได้ ของเดิมจะไม่ถูกลบ"
    }
}