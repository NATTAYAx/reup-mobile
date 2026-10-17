package app.reup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.reup.ui.AppActions
import app.reup.ui.HomeModel
import app.reup.ui.HomeTab
import app.reup.ui.ReupApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The screen this app is opened for.
 *
 * ─── WHAT IT IS NOW ─────────────────────────────────────────────────────────
 *
 * A host. The screens are Compose, in app.reup.ui, drawn from a model that
 * holds what this class used to keep in a dozen fields; this class keeps the
 * clock, the lifecycle, and the things only an activity can do - opening other
 * screens and pages of the system's own settings.
 *
 * ─── WHAT IT USED TO BE, AND WHY THAT HAD TO GO ─────────────────────────────
 *
 * One long column of views built by hand: the list, the settings, the colours
 * and a debugging panel, one under the other. It worked, and it looked like
 * what it was. Changing a theme meant recreate(), because a view reads its
 * colour once; keeping the list current meant a signature of its shape so the
 * views were rebuilt only when it changed. In Compose both of those are what
 * happens on their own when the state they read changes, which is most of why
 * the rewrite is smaller than the screen it replaces.
 *
 * ─── WHAT DID NOT CHANGE ────────────────────────────────────────────────────
 *
 * A tap on a row ticks it and holding it opens it. The sync runs when the app
 * opens, after a tick, and on a clock that slows down when nothing is moving.
 * Nothing runs in the background except two things that have to, each only
 * once it has been turned on: reading a slip a bank's app has just saved, and
 * reading a bank's notification of money coming in. SlipWatch and
 * BankNoticeListener say how little each is. The widget is drawn when
 * something it shows changes, and not otherwise (ReupWidget). The two forms - writing down
 * money, adding a task - are Compose too, in activities of their own; sync
 * settings and the slip reader are the same screens as before for now, opened
 * from here.
 */
class MainActivity : ComponentActivity(), AppActions {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var model: HomeModel
    private var clock: Job? = null

    /**
     * The gallery question, for slips that arrive on their own.
     *
     * Registered here, as a field, because the result of a permission dialog
     * comes back to whatever was registered before the activity started — a
     * registration made later, on a click, finds nothing to come back to.
     */
    private val askPhotos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.photoAccessAnswered()
    }

    /**
     * What was pressed under a slip's picture, done here as if on its row: the
     * same save, the same form, the same "เอาคืน" after throwing it away. A
     * slip settled some other way while its picture was open is no longer in
     * the list, and its answer is dropped.
     */
    private val pictureAnswer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val id = data.getLongExtra(SlipPictureActivity.EXTRA_ID, -1L)
        val slip = PendingSlips.get(this, id) ?: return@registerForActivityResult
        when (data.getStringExtra(SlipPictureActivity.EXTRA_ANSWER)) {
            SlipPictureActivity.SAVE -> model.saveSlip(slip)
            SlipPictureActivity.EDIT -> editSlip(id)
            SlipPictureActivity.DISCARD -> model.dismissSlip(slip)
        }
    }

    /**
     * The two forms, opened from here for a result: what was saved, in words,
     * said along the bottom of the tab the form was opened from, where the new
     * row now is. See finishSaying.
     */
    private val formAnswer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringExtra(EXTRA_SAID)
        if (result.resultCode == RESULT_OK && said != null) model.say(said)
    }

    /**
     * The system's photo picker, for the widget's picture. It hands back the
     * one picture or clip picked and asks for no permission, because it gives
     * none: this app sees what was picked and nothing else.
     */
    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.widget.picked(uri)
    }

    /** The same picker, for the wallpaper's clip. */
    private val pickClip = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.wall.picked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First, before anything is drawn. The screens still opened from here
        // read their colours out of Ui, and the window behind the first frame
        // should already be the page colour rather than the platform's grey.
        Ui.load(this)
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        // Dark bars with nothing painted behind them: the app draws its own
        // page under the status bar and its own tab bar under the gesture strip.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        Notifications.ensureChannel(this)
        requestNotificationPermission()

        model = HomeModel(applicationContext, scope)
        // Opened from the widget. Not on a recreation, which carries the same
        // intent and would undo a tab the person has since moved to.
        if (savedInstanceState == null) openFrom(intent)
        setContent { ReupApp(model, this) }
    }

    /** The widget tapped while the app was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    /** The tab a tap on the widget asked for: the wallet opens money, anywhere else Today. */
    private fun openFrom(intent: Intent?) {
        when (ReupWidget.tabOf(intent)) {
            ReupWidget.OPEN_MONEY -> model.openTab(HomeTab.MONEY)
            ReupWidget.OPEN_TODAY -> model.openTab(HomeTab.TODAY)
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings can only have changed while this screen was away, which is
        // exactly what coming back means.
        model.resume()
        clock?.cancel()
        clock = scope.launch {
            while (isActive) {
                model.tick()
                delay(1000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        clock?.cancel()
        clock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ─── what the screens ask for ───────────────────────────────────────────

    override fun addTask() {
        formAnswer.launch(Intent(this, AddTaskActivity::class.java))
    }

    override fun editTask(uid: String) {
        formAnswer.launch(Intent(this, AddTaskActivity::class.java).putExtra(AddTaskActivity.EXTRA_UID, uid))
    }

    // The note sheet says what it did itself, and the list is read again when
    // this screen comes back, so neither needs an answer handed back.
    override fun addNote() {
        startActivity(Intent(this, AddNoteActivity::class.java))
    }

    override fun editNote(uid: String) {
        startActivity(Intent(this, AddNoteActivity::class.java).putExtra(AddNoteActivity.EXTRA_UID, uid))
    }

    override fun addMoney(incoming: Boolean) {
        formAnswer.launch(Intent(this, AddMoneyActivity::class.java).putExtra(AddMoneyActivity.EXTRA_INCOMING, incoming))
    }

    override fun editMoney(uid: String) {
        formAnswer.launch(Intent(this, AddMoneyActivity::class.java).putExtra(AddMoneyActivity.EXTRA_EDIT_UID, uid))
    }

    override fun openSync() {
        startActivity(Intent(this, SyncActivity::class.java))
    }

    override fun openSlip() {
        startActivity(Intent(this, ScanSlipActivity::class.java))
    }

    override fun fireTest() {
        Scheduler.fireTestIn(this, 60)
    }

    override fun requestPhotoAccess() {
        askPhotos.launch(SlipWatch.permissions())
    }

    override fun editSlip(id: Long) {
        val slip = PendingSlips.get(this, id) ?: return
        formAnswer.launch(PendingSlips.editIntent(this, slip))
    }

    override fun viewSlip(id: Long) {
        pictureAnswer.launch(SlipPictureActivity.intent(this, id, answers = true))
    }

    override fun editIncome(id: String) {
        val income = BankNotices.get(this, id) ?: return
        formAnswer.launch(BankNotices.editIntent(this, income))
    }

    /**
     * The page with this app's own switch where the phone has one, the list of
     * every app that asked where it does not, and the app's page if a maker
     * has moved both. Answered by coming back: HomeModel.resume asks again.
     */
    override fun openNotificationAccess() {
        try {
            startActivity(BankNotices.accessIntent(this))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    override fun pickWidgetPicture() {
        try {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } catch (e: Exception) {
            model.say("เปิดที่เลือกรูปไม่ได้")
        }
    }

    override fun pickWallpaperClip() {
        try {
            pickClip.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } catch (e: Exception) {
            model.say("เปิดที่เลือกคลิปไม่ได้")
        }
    }

    /**
     * Only the system sets a wallpaper, on its own page with its own button.
     * Its preview of this one first; the list of moving wallpapers on a phone
     * whose maker left that out. WallModel hears how it went when this screen
     * comes back.
     */
    override fun setWallpaper() {
        model.wall.opening()
        for (intent in WallClip.setIntents(this)) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // The next one.
            } catch (e: SecurityException) {
                // The next one.
            }
        }
        model.wall.notOpened()
        model.say("เปิดหน้าตั้งวอลเปเปอร์ไม่ได้ ตั้งเองได้ที่ ตั้งค่าเครื่อง → วอลเปเปอร์ → วอลเปเปอร์เคลื่อนไหว แล้วหา Reup คลิป")
    }

    /**
     * The home screen's own "add this widget?" where it has one. Where it has
     * not, the words say how: Android cannot put a widget anywhere by itself.
     */
    override fun placeWidget() {
        if (!ReupWidget.pin(this)) model.say("กดค้างที่หน้าจอหลัก เลือกวิดเจ็ต แล้วหา Reup")
    }

    // ─── system pages ───────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    /**
     * Opens this app's page in the system settings.
     *
     * Deliberately NOT ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which pops
     * a one-tap dialog: Google Play forbids that intent outside a short list of
     * app categories, and this app is not on it. Sending someone to the settings
     * page and saying which switch to look for is slower and allowed.
     *
     * On several brands the switch that matters is in a second place as well,
     * one no intent can open directly. Power.kt names it for the phone this is
     * actually running on, and the diagnostics screen says what to search for.
     */
    override fun openBatterySettings() = openAppSettings()

    override fun openHibernationSettings() {
        val uri = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, uri))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    override fun openAppSettings() {
        val uri = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            Toast.makeText(this, "เปิดหน้าตั้งค่าไม่ได้: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}
