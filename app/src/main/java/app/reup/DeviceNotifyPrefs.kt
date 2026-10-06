package app.reup

import android.content.Context
import app.reup.core.DeviceNotify

/**
 * Which reminders this phone gives - normal, silent, or none.
 *
 * SharedPreferences and never user_settings, on purpose. "Not on the phone
 * tonight, the computer is enough" is a fact about this device, and a setting
 * that travelled would quiet the computer too. The desktop's sound switch
 * stays on the desktop for the same reason; quiet hours and which task may
 * wake somebody are about the person, and those do sync.
 */
object DeviceNotifyPrefs {
    private const val PREFS = "reup_device"
    private const val KEY = "notify_mode"

    fun get(ctx: Context): DeviceNotify =
        DeviceNotify.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(ctx: Context, mode: DeviceNotify) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.id).apply()
    }
}