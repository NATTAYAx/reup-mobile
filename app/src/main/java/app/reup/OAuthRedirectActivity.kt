package app.reup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.reup.sync.SyncValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ─── the one door this app opens to the outside ──────────────────────────────
//
// WHY THIS IS A SEPARATE ACTIVITY
//
// Google sends the person back with a custom scheme, and a scheme can only be
// answered by an exported activity — one any app on the phone is allowed to
// start, with any Intent it likes. Every other component here is exported=false
// and says so with a reason.
//
// SyncActivity could have taken the intent-filter itself. Then the exported
// surface would be the whole settings screen, with its boxes, its saved config
// and its launchMode changed to singleTask for everyone. Instead the surface is
// this file: it reads one Uri, hands it to code that does not trust it, and
// finishes. There is nothing else here to reach.
//
// WHAT PROTECTS IT
//
// Not being hard to find — the scheme is in the manifest and derivable from the
// client id, so assume anyone can send here. What protects it is that the
// redirect is checked against a `state` this app generated and stored before
// the browser opened, and against a verifier only this install has. A crafted
// intent from another app has neither, so it is refused by GoogleOAuth
// .parseRedirect before anything is exchanged. See the comment there.
//
// ─── WHY THE WORK IS NOT OWNED BY THIS ACTIVITY ANY MORE ─────────────────────
//
// The previous version held a CoroutineScope as a field, launched the token
// exchange into it, and cancelled it in onDestroy. Every line of that is the
// normal, correct shape for an activity — and it is wrong here, because of the
// theme.
//
// Theme.NoDisplay means this activity never draws. Android's rule for that is
// that it must have called finish() by the time onResume returns; if it has
// not, the system finishes it for you. The exchange is a network round trip, so
// it is nowhere near done by then. The activity is destroyed, onDestroy cancels
// the scope, and the exchange dies somewhere in the middle — after the pending
// row has already been deleted, so the code cannot even be retried.
//
// The outcome of that is precisely what the phone showed: a sign-in that was
// approved, no error anywhere, and no token stored. The dead giveaway was that
// it was *silent*. Every failure this file can name ends in a toast; the only
// ways to end up with nothing said and nothing saved were "this was not our
// redirect" and "the work never finished".
//
// So the scope is now a file-level one, owned by the process rather than by a
// screen, and finish() is called immediately — which is also what the theme
// wanted all along. The exchange outlives the activity by design. Nothing is
// leaked: it holds the application context and one HTTP call, and if Android
// kills the process mid-flight then nothing was going to survive that anyway.
//
// WHY THE OUTCOME IS WRITTEN DOWN
//
// A toast is the wrong place for the only record of something that happened
// while the person was looking at a browser. It lasts three seconds, it appears
// over whatever screen the phone landed on, and if the app was killed in the
// background it can land after they have already put the phone down.
//
// So every outcome, including success, goes into a row. The settings screen
// reads it. That is the same move as the block on the home screen that says why
// the queue is empty: the fix for "it did nothing and I do not know why" is not
// a better guess, it is writing the answer somewhere it can be read later.

/** Where the last sign-in outcome is written. Read by SyncActivity. */
const val SIGN_IN_NOTE_KEY: String = "sync_google_last"

/** The path after the scheme, as built by AndroidSignIn.redirectUri. */
private const val REDIRECT_PATH = "/oauth2redirect"

/**
 * Process-lifetime, on purpose. See the header. Main, because it ends in a
 * Toast, and everything below it that touches the disk or the network moves
 * itself to Dispatchers.IO already.
 */
private val redirectWork = CoroutineScope(Dispatchers.Main + SupervisorJob())

class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    /** singleTask, so a second redirect arrives here rather than as a new copy. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data
        val clientId = AndroidSignIn.clientId()
        // Captured before finishing. The application context outlives every
        // activity, which is the whole point of using it here.
        val app: Context = applicationContext

        // Before anything else, and before the work is launched. The theme
        // requires it, and the work no longer depends on this screen being
        // alive to complete.
        finish()

        if (uri == null || clientId == null) {
            // Nothing to say and nothing to record. An app that writes a row
            // every time something sends it an intent is an app that can be
            // made to write rows.
            return
        }

        val url = uri.toString()
        if (!url.contains(REDIRECT_PATH)) {
            // Recorded rather than ignored, because this is one of the two ways
            // the old version could end up silent, and telling them apart from
            // the settings screen is the entire reason this row exists.
            //
            // Only the part before the query. Everything after it is the
            // authorisation code, which is a credential, and a credential
            // written into a settings row is a credential in the next backup.
            redirectWork.launch { note(app, "เด้งกลับมาที่แอปแต่ไม่ใช่ redirect ที่รออยู่ " + url.substringBefore('?')) }
            return
        }

        redirectWork.launch {
            val result = try {
                AndroidSignIn(AndroidDb.shared(app), AndroidHttpTransport())
                    .finish(uri, clientId)
            } catch (e: Exception) {
                // Kept as it came. An unexpected failure rewritten into a
                // friendly sentence is one nobody can report.
                AndroidSignIn.Result.Failed(e.message ?: e.toString())
            }

            when (result) {
                is AndroidSignIn.Result.Connected -> {
                    // Still nothing on screen: the settings screen shows the
                    // button as connected the moment it comes back to the
                    // front, and that is a better answer than a toast. The row
                    // is written anyway, so that "it says connected" and "the
                    // sign-in actually completed" are two separate claims that
                    // can be compared when they disagree.
                    note(app, "เชื่อมสำเร็จ")
                }
                is AndroidSignIn.Result.Refused -> {
                    note(app, result.why)
                    toast(app, result.why)
                }
                is AndroidSignIn.Result.Failed -> {
                    note(app, result.why)
                    toast(app, result.why)
                }
                // The path check above already caught the ordinary version of
                // this; reaching here means the Uri contained the path and the
                // handler still did not recognise it.
                null -> note(app, "redirect มาถึงแต่ตัวอ่านไม่รับ")
            }
        }
    }

    private suspend fun note(ctx: Context, text: String) {
        try {
            AndroidDb.shared(ctx).execute(
                "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                listOf(SyncValue.Text(SIGN_IN_NOTE_KEY), SyncValue.Text(text)),
            )
        } catch (e: Exception) {
            // Recording why something failed must not itself be able to fail
            // loudly. Losing the note is a worse screen, not a broken app.
        }
    }

    private fun toast(ctx: Context, text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }
}