package app.reup

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import app.reup.ui.AppActions
import app.reup.ui.HomeModel
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
 * Nothing runs in the background except the one thing that has to — reading a
 * slip a bank's app has just saved, when that has been turned on; SlipWatch
 * says how little that is. The other screens - adding a task, writing down
 * money, sync settings, the slip reader - are the same screens as before for
 * now, opened from here.
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
        setContent { ReupApp(model, this) }
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
        startActivity(Intent(this, AddTaskActivity::class.java))
    }

    override fun editTask(uid: String) {
        startActivity(Intent(this, AddTaskActivity::class.java).putExtra(AddTaskActivity.EXTRA_UID, uid))
    }

    override fun addMoney(incoming: Boolean) {
        startActivity(Intent(this, AddMoneyActivity::class.java).putExtra(AddMoneyActivity.EXTRA_INCOMING, incoming))
    }

    override fun editMoney(uid: String) {
        startActivity(Intent(this, AddMoneyActivity::class.java).putExtra(AddMoneyActivity.EXTRA_EDIT_UID, uid))
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
        startActivity(PendingSlips.editIntent(this, slip))
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
