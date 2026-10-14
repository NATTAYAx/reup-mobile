package app.reup

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.reup.core.paletteOf
import app.reup.ui.ReupTheme
import app.reup.ui.TaskFormModel
import app.reup.ui.TaskFormScreen
import app.reup.ui.reupColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The other direction.
 *
 * Until now everything in the database on this phone arrived by sync. A task
 * thought of on a bus went into a note somewhere and got typed in later at the
 * desk, or did not. This is the screen that closes that.
 *
 * WHAT THIS CLASS IS NOW
 * ----------------------
 * A host, like MainActivity and AddMoneyActivity. The form is TaskFormScreen,
 * drawn from TaskFormModel, out of the same pieces as the money form; this
 * class reads which task it was opened for and owns the lifecycle. It used to
 * be five hundred lines of views built by hand, with the time typed as "04:00"
 * and the date as "2026-09-02".
 *
 * WHY IT IS STILL THIN
 * --------------------
 * Same reason SyncActivity and AndroidDb are. The module no test reaches holds
 * as little as it can: which columns a task has, how each value is read, what
 * makes a draft unschedulable and how a picked time is written all live in the
 * shared module, against vectors the desktop generates and tests of their own.
 *
 * WHY EDITING IS THIS SCREEN AND NOT ANOTHER ONE
 * ----------------------------------------------
 * An edit form is an add form with the fields already filled in. Two would
 * mean two copies of every field, chip and sentence, in the one module where
 * nothing can be tested. With a uid it loads a row and updates it; without
 * one it creates.
 */
class AddTaskActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var model: TaskFormModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything is drawn, so the window behind the first frame is
        // already the page colour and the theme is the one picked.
        Ui.load(this)
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        model = TaskFormModel(applicationContext, scope, intent.getStringExtra(EXTRA_UID), done = { finishSaying(it) })
        val colors = reupColors(paletteOf(Ui.themeId(this)))
        setContent {
            ReupTheme(colors) {
                TaskFormScreen(model, onBack = ::finish)
            }
        }
        model.load()
    }

    override fun onResume() {
        super.onResume()
        model.resume()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Which task to edit. Absent means make a new one. */
        const val EXTRA_UID = "app.reup.uid"
    }
}
