package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// ─── UserSettings.kt — the settings that belong to a person, not a machine ────
//
// Mirror of src/lib/userSettings.ts.
//
// WHAT THIS FIXES
//
// Repo.kt has carried a hardcoded 23:00 to 08:00 with a comment saying why:
// quiet hours lived in `app_settings`, which is deliberately not synced because
// it holds the pairing key and the WebDAV password, so this phone had no way to
// learn what night means on the machine the person actually uses. Without the
// default, an app that had been silent at night for months would have started
// ringing at 04:00 the day the tasks became real.
//
// `user_settings` is that half of the table promoted into one of its own. What
// stays behind is the machine's own business, and none of it is one line of
// policy away from being sent. See schema.sql.
//
// ─── WHY THE VALUE IS THE DESKTOP'S localStorage STRING, VERBATIM ────────────
//
// It would read better as columns. It would also be a second format, with a
// writer on one side and a reader on the other and nothing forcing them to
// agree — which is the shape of every bug this project has spent a month
// removing. So the row carries exactly the bytes the desktop already had, and
// the only thing standing between one format and two readings of it is the
// function below, which has vectors.

/** `{ enabled, start, end }`, as the desktop's notifier.ts writes it. */
const val KEY_QUIET = "gamesched_quiet_hours_v1"

/** An ISO 4217 code. */
const val KEY_CURRENCY = "gamesched_currency"

/** "th" or "en". */
const val KEY_LANG = "gamesched_lang_v1"

/**
 * One live setting by name.
 *
 * `deleted = 0` because a tombstone is a key that was retired, not a key set to
 * nothing, and a screen that reads a retired key's old value is a screen showing
 * something the person removed.
 */
fun userSettingSql(): String =
    "SELECT value FROM user_settings WHERE key = ? AND deleted = 0"

/**
 * What a stored quiet-hours value means, with "nothing stored" kept separate
 * from "off".
 *
 * The distinction is the entire point on this device. A phone that has never
 * synced knows nothing and should fall back to a sensible night; a phone that
 * has synced and been told quiet hours are off must not put them back.
 * Collapsing the two into a nullable makes the second case indistinguishable
 * from the first, and the failure is an alarm at four in the morning that
 * somebody explicitly turned off.
 */
sealed interface QuietSetting {
    /** No row, or nothing readable in it. The caller picks a default. */
    data object Unknown : QuietSetting

    /** Said, and said no. */
    data object Off : QuietSetting

    data class Window(val start: String, val end: String) : QuietSetting
}

private val json = Json { ignoreUnknownKeys = true }

private val HHMM = Regex("""^\d{2}:\d{2}$""")

/**
 * Read the stored string, tolerating everything a stored string can be.
 *
 * Anything unreadable is [QuietSetting.Unknown] rather than a throw. This is
 * parsed inside a broadcast receiver on the way to setting eight alarms, and a
 * settings row somebody edited by hand should cost a default rather than a
 * scheduler that refuses to run.
 */
fun parseQuiet(raw: String?): QuietSetting {
    if (raw.isNullOrEmpty()) return QuietSetting.Unknown
    val o = try {
        json.parseToJsonElement(raw) as? JsonObject ?: return QuietSetting.Unknown
    } catch (e: Exception) {
        return QuietSetting.Unknown
    }

    val enabled = o["enabled"] as? JsonPrimitive ?: return QuietSetting.Unknown
    // Only a real boolean counts, and `isString` is the half that is easy to
    // leave out: `booleanOrNull` reads the text of the primitive without caring
    // whether it was quoted, so the JSON string "true" comes back as true here
    // while `=== true` on the desktop says no. The vector for that case is in
    // store-vectors.json because this exact line got it wrong first.
    if (enabled.isString || enabled.booleanOrNull != true) return QuietSetting.Off

    val start = (o["start"] as? JsonPrimitive)?.contentOrNull?.takeIf { HHMM.matches(it) }
    val end = (o["end"] as? JsonPrimitive)?.contentOrNull?.takeIf { HHMM.matches(it) }
    // Switched on but with an unreadable window is not off — somebody asked for
    // quiet and the numbers were lost. Unknown lets each side apply its own
    // default night rather than silently deciding there is none.
    if (start == null || end == null) return QuietSetting.Unknown
    // Equal bounds are off rather than a whole day of silence, the same as the
    // desktop has always done. Someone who sets both to 08:00 has made a
    // mistake, and losing every reminder for ever is not the failure to pick.
    if (start == end) return QuietSetting.Off
    return QuietSetting.Window(start, end)
}

/** Exposed for the vector suite and for anything that needs to name a key. */
object UserSettings {
    const val QUIET = KEY_QUIET
    const val CURRENCY = KEY_CURRENCY
    const val LANG = KEY_LANG
}