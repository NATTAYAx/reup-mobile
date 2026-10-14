package app.reup

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.reup.core.paletteOf
import app.reup.ui.MoneyFormModel
import app.reup.ui.MoneyFormScreen
import app.reup.ui.MoneyFormStart
import app.reup.ui.ReupTheme
import app.reup.ui.reupColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Money, written down where it happens.
 *
 * The money going out is spent standing up — at a counter, on a bus, in a shop
 * — and the machine it was being recorded on is at a desk. Everything about
 * that gap is why the numbers in the app have never quite been the numbers.
 *
 * WHAT THIS CLASS IS NOW
 * ----------------------
 * A host, like MainActivity. The form is MoneyFormScreen, drawn from
 * MoneyFormModel, in the same pieces as the money tab; this class reads what it
 * was opened with, owns the lifecycle, and opens the slip's picture. It used to
 * be nine hundred lines of views built by hand, with the day typed into a box
 * as 2026-10-13 and the last entries printed as "08/14 food -60 THB".
 *
 * WHY BOTH DIRECTIONS ARE ONE SCREEN
 * ----------------------------------
 * They differ in two controls. Spending has category chips read from the
 * database; a payment has a box for the name on it. Everything else — the
 * amount, the unit beside it, the note, the date, every sentence the validator
 * can say — is the same, because the shared layer answers the same questions
 * for both and returns the same codes. The add and edit forms for tasks are
 * one screen for the same reason.
 *
 * WHY THE CURRENCY IS ON SCREEN AND NOT ASSUMED
 * ---------------------------------------------
 * A number filed in the wrong unit is not visibly wrong anywhere. It is not a
 * red row or a failed save; it is simply in the wrong total, and every screen
 * that reads that total filters by unit and quietly leaves it out. So the unit
 * is printed next to the amount, always, and why it might be wrong is printed
 * under it. A person who sees THB when they meant USD can see it before
 * pressing anything.
 *
 * WHY THE LAST FEW ENTRIES ARE ON THIS SCREEN
 * -------------------------------------------
 * A total cannot say whether the coffee at eleven went in twice: two identical
 * rows move it by exactly what one row for twice the price would. So the last
 * few are under the form, to look at before saving one more.
 *
 * WHY A SAVE GOES BACK, AND SAYS SO
 * ---------------------------------
 * Closing on success once looked exactly like a crash: the only sign a row had
 * landed was the screen being gone. So the form stayed, emptied for the next
 * row - and on the phone that looked like the form starting over, as if the
 * save were the one thing that had not happened. Now it goes back, and what
 * was saved is said where it lands: along the bottom of the money tab, which
 * has the new row at the head of its list, or as a toast when the form was
 * opened from a notification or the slip reader.
 *
 * WHY IT DOES NOT REFUSE WHEN THERE IS NO SETTING YET
 * ---------------------------------------------------
 * A phone that has not synced has no currency row, and refusing to record
 * anything until it has is a worse answer than the desktop's own: that one
 * guesses once from the machine and writes the guess down. This falls back to
 * the same value money.ts falls back to and says so on screen, which keeps the
 * number and makes the assumption visible instead of silent.
 */
class AddMoneyActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var model: MoneyFormModel

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
        model = MoneyFormModel(applicationContext, scope, opened(), done = { finishSaying(it) })
        val colors = reupColors(paletteOf(Ui.themeId(this)))
        setContent {
            ReupTheme(colors) {
                MoneyFormScreen(model, onBack = ::finish, onPicture = ::showPicture)
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

    /** The slip's picture, whole. No answers on it: the answers are this form. */
    private fun showPicture(id: Long) {
        startActivity(SlipPictureActivity.intent(this, id, answers = false))
    }

    /**
     * What whoever opened this asked for: the slip reader, a slip that arrived
     * on its own, money in off a bank's notification, or a row on the money
     * tab.
     *
     * A reading is handed over rather than written, because a row is written
     * when a person presses save with the numbers in front of them. A slip that
     * saved itself would be a way in for numbers a recogniser guessed at, which
     * is the one kind of row nobody would think to check. The save button on a
     * slip with nothing left to check (PendingSlips.save) is the same gate with
     * a second door handle: a person pressing save on a reading they can see,
     * through the same repository call and the same validation.
     */
    private fun opened(): MoneyFormStart = MoneyFormStart(
        incoming = intent.getBooleanExtra(EXTRA_INCOMING, false),
        editUid = intent.getStringExtra(EXTRA_EDIT_UID),
        amount = intent.getStringExtra(EXTRA_AMOUNT)?.trim()?.takeIf { it.isNotEmpty() },
        date = intent.getStringExtra(EXTRA_DATE)?.trim()?.takeIf { it.isNotEmpty() },
        note = intent.getStringExtra(EXTRA_NOTE)?.trim()?.takeIf { it.isNotEmpty() },
        currency = intent.getStringExtra(EXTRA_CURRENCY)?.trim()?.uppercase()
            ?.takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } },
        currencyUnsure = intent.getBooleanExtra(EXTRA_CURRENCY_UNSURE, false),
        category = intent.getStringExtra(EXTRA_CATEGORY),
        slipRef = intent.getStringExtra(EXTRA_SLIP_REF)?.trim()?.takeIf { it.isNotEmpty() },
        slipId = intent.getLongExtra(EXTRA_SLIP_ID, -1L),
        slipCheck = intent.getStringExtra(EXTRA_SLIP_CHECK)?.takeIf { it.isNotBlank() },
        noticeId = intent.getStringExtra(EXTRA_NOTICE_ID)?.takeIf { it.isNotBlank() },
    )

    companion object {
        /** Open straight into the payment side. Absent means spending. */
        const val EXTRA_INCOMING = "app.reup.incoming"

        // Opened with a form already filled in. Strings rather than numbers:
        // what arrives is what a recogniser read, and the person is about to
        // look at it in a box either way.
        const val EXTRA_AMOUNT = "app.reup.amount"
        const val EXTRA_DATE = "app.reup.date"
        const val EXTRA_NOTE = "app.reup.note"

        /** An ISO code read off a slip: THB, USD, JPY. */
        const val EXTRA_CURRENCY = "app.reup.currency"

        /** The slip's money sign is one several currencies share ($, ¥, kr). */
        const val EXTRA_CURRENCY_UNSURE = "app.reup.currency_unsure"

        /** Open one existing row for editing, by uid. Saving goes back. */
        const val EXTRA_EDIT_UID = "app.reup.edit_uid"

        /** A category key a slip's memo named. Ignored when it is not one of the chips. */
        const val EXTRA_CATEGORY = "app.reup.category"

        /** The slip's reference, for slip_ref. */
        const val EXTRA_SLIP_REF = "app.reup.slip_ref"

        /** A slip that arrived on its own, by gallery id. Saving takes it off the list. */
        const val EXTRA_SLIP_ID = "app.reup.slip_id"

        /** Why that slip could not be saved with one press, as a sentence. */
        const val EXTRA_SLIP_CHECK = "app.reup.slip_check"

        /** Money in read off a bank's notification, by its id. Saving takes it off the list. */
        const val EXTRA_NOTICE_ID = "app.reup.notice_id"
    }
}
