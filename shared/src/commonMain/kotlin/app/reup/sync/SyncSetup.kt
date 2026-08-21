package app.reup.sync

// ─── SyncSetup.kt — the decisions behind four boxes and two buttons ──────────
//
// The screen itself lives in the Android module, which is the one place no test
// here can reach. So everything in it that could be wrong lives here instead:
// what goes in the boxes when it opens, what a half-filled form does, and which
// mistakes are refused outright rather than saved.
//
// All three of the refusals are about the pairing code, and they are all the
// same mistake wearing different clothes. The code is the bucket id and the
// encryption key in one string. Nobody can recover it — not the server, not
// this app, not the person who wrote it. So the rule this file exists to hold
// is that no path through the setup screen can end with a working code being
// replaced by something that is not one.
//
// There are three ways to try:
//
//   Leaving the box empty. Reads as "I did not change it", not as "delete it".
//   An empty field is the most likely thing on a screen someone opened to fix
//   the folder address, and interpreting it as a deletion would destroy a key
//   as a side effect of correcting a typo somewhere else.
//
//   Typing something that is not a code. Refused, and nothing at all is saved,
//   including the folder settings that were fine. Saving half of the form and
//   refusing the other half leaves the person looking at a screen that shows a
//   code they did not enter, and no way to tell which parts went in.
//
//   Pressing save on a code that was already unreadable. Accepted unchanged.
//   A code kept-but-broken is deliberate — Config.kt refuses to throw one away
//   — so re-saving what is already there must not be the thing that removes it.
//
// The phone cannot mint a code at all, on purpose, so none of this is about
// creating one. That happens once, on the desktop, on a screen wide enough to
// say plainly what losing it costs.
//
// ─── WHY THE BACKEND IS NOW A FIELD RATHER THAN AN INFERENCE ─────────────────
//
// It used to be read off the address box: something typed there meant WebDAV,
// nothing typed there meant off. That is a complete rule for two backends and a
// silently wrong one for three, and the third already existed everywhere else —
// Config could parse `drive`, serialise `drive` and build a GoogleDriveStorage
// from it, and the phone could sign in to Google and store a refresh token.
//
// What no line of code on this device could do was write the word `drive` into
// the settings row. So signing in worked, the token was saved, and every sync
// afterwards still went to the WebDAV folder, reporting success. Nothing
// errored, which is what made it cost an evening.
//
// It is the same shape as the bug Config.kt's own header describes on the
// desktop, only mirrored: there the screen remembered Drive while the database
// said off, here the screen offers Drive while the database says WebDAV. Both
// come from one fact being derived in one place and stored in another. The
// choice is a field now, it comes back out of `fieldsOf`, and the compiler asks
// about it at every `when`.

/** Which of the two places the files go. Off is not here: off is an empty address. */
enum class BackendChoice { WEBDAV, DRIVE }

/** Exactly what the screen holds, as text plus the one choice that is not text. */
data class SyncFields(
    val baseUrl: String,
    val username: String,
    val password: String,
    val pairing: String,
    /**
     * Last, and defaulted, so that every existing caller and test that names
     * four values still means what it meant. WebDAV is the right default for
     * them: it is what "an address was typed" used to imply.
     */
    val backend: BackendChoice = BackendChoice.WEBDAV,
)

enum class SetupProblem {
    /** The address cannot be used at all — wrong scheme, or plain http to the internet. */
    UNUSABLE_URL,

    /** Something was typed in the code box that is not a pairing code. */
    UNREADABLE_CODE,
}

sealed interface SetupResult {
    /** Ready to persist. Nothing has been written yet; that is the caller's job. */
    data class Accepted(val config: SyncConfig) : SetupResult

    /**
     * Nothing is written, and the reason is one the screen can put into a
     * sentence. [detail] carries the underlying message for the address case,
     * because "use https" and "that is not a URL" are different problems with
     * different fixes and flattening them helps nobody.
     */
    data class Refused(val problem: SetupProblem, val detail: String? = null) : SetupResult
}

/** What one run did, in the four numbers a person can act on. */
data class SyncSummary(
    val read: Int,
    val applied: Int,
    val pushed: Int,
    val skipped: Int,
    /**
     * Nothing moved in either direction.
     *
     * Worth its own flag rather than being left as three zeros to notice,
     * because it is the expected answer to pressing the button twice, and the
     * screen should say "both sides already agree" rather than show a row of
     * zeros that reads like a failure.
     *
     * It is worth knowing what it does not say. Quiet means this run moved
     * nothing between this device and the folder it was pointed at. It cannot
     * tell "we already agree" apart from "nobody writes here any more", because
     * from inside one device those two look identical. The way to tell them
     * apart is to change one row on the other machine and press the button
     * again; there is no flag that can answer it from here.
     */
    val quiet: Boolean,
)

object SyncSetup {

    /** What to put on the screen when it opens. */
    fun fieldsOf(c: SyncConfig): SyncFields = when (val b = c.backend) {
        // Off keeps the WebDAV side selected, because off is what an empty
        // address box means and that is the box being shown.
        SyncBackend.Off -> SyncFields("", "", "", c.pairing ?: "", BackendChoice.WEBDAV)
        is SyncBackend.WebDav ->
            SyncFields(b.baseUrl, b.username, b.password, c.pairing ?: "", BackendChoice.WEBDAV)
        // Drive has no address, no username and no password, so those three
        // boxes are empty — and the screen hides them, because three boxes that
        // do nothing is a screen that says something untrue. The pairing code is
        // not one of them: it belongs to the data, not to the way the data
        // travels, and it is the one value here nobody can reissue.
        SyncBackend.Drive -> SyncFields("", "", "", c.pairing ?: "", BackendChoice.DRIVE)
    }

    /**
     * The form as typed, turned into either something to save or a reason not to.
     *
     * The address is trimmed, because a trailing space pasted along with a URL
     * is not a decision anyone made. The password is not, because a space at
     * either end of a password is a character in the password.
     */
    fun apply(previous: SyncConfig, typed: SyncFields): SetupResult {
        val code = typed.pairing.trim()
        val pairing: String? = when {
            // Unchanged, whatever it is. Includes the kept-but-broken case.
            code == (previous.pairing ?: "") -> previous.pairing
            // Empty means "I did not touch this", never "throw the key away".
            code.isEmpty() -> previous.pairing
            else -> {
                try {
                    PairingCode.decode(code)
                } catch (e: CryptoException) {
                    return SetupResult.Refused(SetupProblem.UNREADABLE_CODE)
                }
                code
            }
        }

        // Decided before the address is looked at, and deliberately without
        // checking whether anyone has signed in to Google yet. Choosing Drive
        // is a statement about where the files should go; being signed in is a
        // condition that comes and goes and is reported by the sync button.
        // Refusing to save the choice until the sign-in exists would mean the
        // setting quietly reverting every time a token expired.
        if (typed.backend == BackendChoice.DRIVE) {
            // Whatever is in the three WebDAV boxes is dropped, exactly as the
            // desktop drops it: `parse` on both sides reads a drive row as
            // having no address, so keeping one here would be a value that
            // survives in memory and not on disk. That is the class of bug this
            // whole file exists to avoid.
            return SetupResult.Accepted(SyncConfig(SyncBackend.Drive, pairing))
        }

        val url = typed.baseUrl.trim()
        if (url.isEmpty()) {
            // No folder is a complete answer: sync off, key untouched. Same
            // shape the desktop's switch leaves behind.
            return SetupResult.Accepted(SyncConfig(SyncBackend.Off, pairing))
        }

        try {
            WebDav.assertUsableUrl(url)
        } catch (e: StorageException) {
            return SetupResult.Refused(SetupProblem.UNUSABLE_URL, e.message)
        }

        return SetupResult.Accepted(
            SyncConfig(
                SyncBackend.WebDav(baseUrl = url, username = typed.username, password = typed.password),
                pairing,
            ),
        )
    }

    /**
     * Quiet is decided by what moved, not by what was read.
     *
     * A run that downloads three batches and finds it already has every row in
     * them did nothing, and saying so is more useful than reporting the three.
     * Skipped files are counted separately and never fold into quiet: a file
     * that could not be read is the one thing here worth coming back to.
     */
    fun summarise(r: SyncReport): SyncSummary = SyncSummary(
        read = r.read,
        applied = r.applied,
        pushed = r.pushed,
        skipped = r.skipped.size,
        quiet = r.applied == 0 && r.pushed == 0,
    )
}