package app.reup

import android.app.Activity
import android.content.Intent
import android.widget.Toast

/** In a form's result: what it did, in words, for the screen that opened it to say. */
const val EXTRA_SAID = "app.reup.said"

/**
 * Leave a form, saying what it did - "บันทึกแล้ว · ข้าวมันไก่ −฿45".
 *
 * Opened for a result, as the main screen opens both forms, the words go back
 * in the result and that screen says them along its bottom, over the list the
 * new row is now in. Opened any other way - a notification, the slip reader -
 * there is no such screen to come back to, so a toast says them. Either way a
 * save is never only a screen disappearing, which is what a crash looks like.
 *
 * @param said null to leave without a word, as cancelling does
 */
fun Activity.finishSaying(said: String?) {
    if (said != null) {
        if (callingActivity != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SAID, said))
        } else {
            Toast.makeText(applicationContext, said, Toast.LENGTH_SHORT).show()
        }
    }
    finish()
}
