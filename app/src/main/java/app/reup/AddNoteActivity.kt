package app.reup

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.reup.core.paletteOf
import app.reup.ui.NoteSheet
import app.reup.ui.NoteSheetModel
import app.reup.ui.ReupTheme
import app.reup.ui.reupColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Writing a note, from the widget's + or from a note on it, and from the
 * notes on the Today tab: a sheet over whatever was on screen, so the home
 * screen is still there behind it and is what comes back when it closes.
 *
 * A host and nothing more, like AddTaskActivity: what a note may say and how
 * it is written live in the shared module (Notes.kt, NoteRepo.kt), against the
 * desktop's vectors. Its own task and out of the recents list, so sticking a
 * note up from the home screen does not bring the rest of the app forward
 * behind it.
 */
class AddNoteActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(this)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val uid = ReupWidget.noteOf(intent) ?: intent.getStringExtra(EXTRA_UID)
        val model = NoteSheetModel(applicationContext, scope, uid) { said ->
            if (said != null) Toast.makeText(applicationContext, said, Toast.LENGTH_SHORT).show()
            finish()
        }
        val colors = reupColors(paletteOf(Ui.themeId(this)))
        setContent {
            ReupTheme(colors) {
                NoteSheet(model, onDismiss = ::finish)
            }
        }
        model.load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Which note to change, from inside the app. Absent means write a new one. */
        const val EXTRA_UID = "app.reup.note"
    }
}
