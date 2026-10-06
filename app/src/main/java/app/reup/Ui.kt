// app/src/main/java/app/reup/Ui.kt
package app.reup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.reup.core.DEFAULT_PALETTE
import app.reup.core.Palette
import app.reup.core.THEMES
import app.reup.core.paletteOf

/**
 * One place that decides what the phone looks like.
 *
 * ─── WHAT CHANGED THIS ROUND, AND WHY ───────────────────────────────────────
 *
 * The last round put all five screens on one design. This one fixes what was
 * still wrong once they were all on it, and all of it was only visible on a
 * real phone.
 *
 * 1. THE APP WAS DRAWING UNDERNEATH THE SYSTEM BARS.
 *
 *    targetSdk is 37. Since Android 15 an app at that target is edge to edge
 *    whether it asked to be or not, and nothing here had ever asked where the
 *    system bars were. So the air above the title was spent underneath the
 *    status bar, and the two buttons at the bottom sat under the navigation
 *    bar — half covered, and in gesture mode sharing their lower edge with the
 *    swipe that leaves the app.
 *
 *    That is not a spacing mistake, it is the app not knowing what shape the
 *    screen is. See [fitSystemBars], which is also the answer to a notch, a
 *    curved edge, three buttons instead of a bar, and landscape.
 *
 * 2. THE COLOURS WERE SIXTEEN CONSTANTS IN THIS FILE.
 *
 *    The desktop has had a theme picker for months. The phone had one violet,
 *    unchangeable without editing Kotlin and rebuilding. They come from
 *    Palette.kt now, as data, with a readability test — which caught two themes
 *    on its first run where the word on the button was too pale to read
 *    outdoors.
 *
 * 3. THE LIST REPEATED ITSELF.
 *
 *    Four daily tasks that all reset at midnight all get pushed to the same
 *    time by quiet hours, so four rows read the identical sentence at full
 *    width. Days are headings now and the clock has a column of its own; see
 *    [sectionHead] and [taskRow], and homeSections in Face.kt for the rule.
 *
 * ─── WHAT DID NOT CHANGE ────────────────────────────────────────────────────
 *
 * Everything is still in points through [dp], every tap target is still at
 * least 48, and there is still exactly one radius and one row height. A screen
 * that needs a colour this file does not have is a screen about to invent one,
 * which is how five screens ended up with five greys.
 */
object Ui {

    // ─── colour ─────────────────────────────────────────────────────────────
    //
    // Read through the palette rather than declared here, so that changing a
    // theme changes the app rather than changing a file. Every name below is
    // the one the screens already used, so nothing else had to move.

    var palette: Palette = DEFAULT_PALETTE
        private set

    val BG: Int get() = palette.bg
    val CARD: Int get() = palette.card
    val RAISED: Int get() = palette.raised
    val FIELD: Int get() = palette.field
    val LINE: Int get() = palette.line
    val PRIMARY: Int get() = palette.primary
    val ON_PRIMARY: Int get() = palette.onPrimary
    val ACCENT: Int get() = palette.accent
    val TEXT: Int get() = palette.text
    val DIM: Int get() = palette.dim
    val FAINT: Int get() = palette.faint
    val POSITIVE: Int get() = palette.positive
    val WARN: Int get() = palette.warn
    val DANGER: Int get() = palette.danger

    private const val PREFS = "reup_look"
    private const val KEY_THEME = "theme"

    /**
     * Which theme this phone is on. Called first thing in every onCreate.
     *
     * WHY SHAREDPREFERENCES AND NOT THE DATABASE
     *
     * A screen cannot wait to find out what colour it is. Reading the database
     * is suspend, the first view is built before any coroutine has run, and a
     * screen that starts in one theme and repaints into another a moment later
     * is worse than a screen with no choice at all.
     *
     * IT SYNCS NOW, AND THIS IS STILL THE COPY THE SCREEN READS
     *
     * The note that used to be here said the theme could not sync because the
     * desktop kept a whole palette object, and half a shared setting is worse
     * than none. That round has happened: what crosses is the id, and each
     * machine looks up its own colours under that name, so the palette the
     * desktop works out from a wallpaper video stays where it belongs.
     *
     * This is still where the screen reads from, for the reason above. The row
     * is the truth and this is the copy that can answer instantly; [adopt] is
     * what brings the copy in line, and it runs after a sync rather than during
     * a layout pass.
     */
    fun load(ctx: Context) {
        palette = paletteOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null),
        )
    }

    fun themeId(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
            ?: DEFAULT_PALETTE.id

    fun setTheme(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, id).apply()
        palette = paletteOf(id)
    }

    /**
     * Take the theme the other machine picked, if it named one this build has.
     *
     * Returns whether anything moved, so the caller can rebuild its views. It
     * has to rebuild them: a colour is read once, when a view is made, which is
     * the same reason picking a theme on this screen calls recreate().
     *
     * THE ONE RULE
     *
     * An id this build does not know is not a change this build can make.
     *
     * The desktop stores a palette it worked out from a wallpaper video under
     * the id "custom", which means something there and nothing here. Falling
     * back to the default for a name this build does not have would turn that
     * into the phone flipping to violet - not because anybody picked violet,
     * but because somebody picked a colour on a machine this one cannot
     * follow. The same holds for whatever a later version adds, either way.
     */
    fun adopt(ctx: Context, id: String?): Boolean {
        if (id == null || THEMES.none { it.id == id }) return false
        if (id == themeId(ctx)) return false
        setTheme(ctx, id)
        return true
    }

    // ─── scale ──────────────────────────────────────────────────────────────
    //
    // Text in sp, so it follows the system font size. Someone who turned their
    // phone's text up did that on purpose, and every height below is
    // wrap_content so rows grow instead of clipping when they do.

    private const val SIZE_TITLE = 20f
    private const val SIZE_ROW = 15f
    private const val SIZE_BODY = 14f
    private const val SIZE_NOTE = 12f
    private const val SIZE_LABEL = 11f
    private const val SIZE_MONO = 12f
    private const val SIZE_CLOCK = 14f

    private const val RADIUS = 14f
    private const val RADIUS_SMALL = 12f
    private const val TAP = 48f

    /**
     * The widest the reading column is allowed to get.
     *
     * A phone never reaches it. A tablet, a foldable opened out, or a phone
     * turned sideways does, and a line of text the full width of a 900 point
     * screen is one the eye cannot find its way back to the start of. Past
     * this, extra width becomes margin.
     */
    private const val MAX_CONTENT = 560f

    /** Points to pixels, on whatever screen this is running on. */
    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            ctx.resources.displayMetrics,
        ).toInt()

    /** The same colour, see-through. [a] is 0..255. */
    fun fade(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    // ─── the shell ──────────────────────────────────────────────────────────

    class Screen(val scroll: ScrollView, val column: LinearLayout)

    fun screen(ctx: Context): Screen {
        val column = column(ctx)
        column.setPadding(dp(ctx, 20f), dp(ctx, 16f), dp(ctx, 20f), dp(ctx, 40f))

        val scroll = ScrollView(ctx)
        scroll.setBackgroundColor(BG)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return Screen(scroll, column)
    }

    /**
     * A screen whose main action is pinned above the navigation bar.
     *
     * The bar exists because a save button at the end of a growing column falls
     * off the bottom of the screen — a bug the desktop found and fixed in its
     * own add-task form months before the phone had one. It is also where a
     * thumb reaches, which on a screen this tall the top is not.
     */
    class Sticky(
        val root: FrameLayout,
        val scroll: ScrollView,
        val column: LinearLayout,
        val bar: LinearLayout,
    )

    fun sticky(ctx: Context): Sticky {
        val plain = screen(ctx)

        val bar = strip(ctx)
        val wrap = column(ctx)
        // Opaque: the column scrolls underneath, and text sliding out from
        // behind a button reads as a glitch.
        wrap.setBackgroundColor(BG)
        wrap.addView(
            divider(ctx),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)),
        )
        wrap.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val root = FrameLayout(ctx)
        root.setBackgroundColor(BG)
        root.addView(
            plain.scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            wrap,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        fitSystemBars(root, plain.column, wrap, bar)
        return Sticky(root, plain.scroll, plain.column, bar)
    }

    /**
     * Ask the system where its bars are, and stay out of them.
     *
     * WHY IT IS DONE HERE RATHER THAN IN A LAYOUT FILE
     *
     * There are no layout files, and there is no AndroidX in this module — no
     * WindowInsetsCompat, no ViewCompat. The framework listener is the whole
     * API available, and it is enough.
     *
     * WHAT IS ASKED FOR
     *
     * systemBars covers the status bar and whichever navigation this phone is
     * using, three buttons or a gesture strip. displayCutout is added because a
     * hole punch or a notch is only reported there, and in landscape it is the
     * thing that would otherwise eat the first letter of every row.
     *
     * WHY THE COLUMN GETS THE TOP AND THE BAR GETS THE BOTTOM
     *
     * They are different jobs. The top is reading space and belongs to the
     * scrolling content. The bottom is a thumb's distance from the edge of the
     * glass and belongs to the bar, which is the thing being reached for.
     * Padding the bar rather than moving it also keeps the bar's own background
     * filling the space behind the navigation, instead of leaving a stripe of
     * page showing through.
     *
     * WHY THE INSETS ARE NOT CONSUMED
     *
     * Returning them unchanged costs nothing here and keeps the behaviour
     * correct if anything is ever nested inside one of these screens.
     */
    private fun fitSystemBars(
        root: FrameLayout,
        column: LinearLayout,
        wrap: LinearLayout,
        bar: LinearLayout,
    ) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            var top = 0
            var bottom = 0
            var left = 0
            var right = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                top = i.top
                bottom = i.bottom
                left = i.left
                right = i.right
            } else {
                // minSdk is 26, so this is not dead code for a few years yet.
                @Suppress("DEPRECATION")
                run {
                    top = insets.systemWindowInsetTop
                    bottom = insets.systemWindowInsetBottom
                    left = insets.systemWindowInsetLeft
                    right = insets.systemWindowInsetRight
                }
            }
            val ctx = v.context
            val side = sideMargin(ctx, v.width)
            column.setPadding(
                side + left,
                dp(ctx, 16f) + top,
                side + right,
                column.paddingBottom,
            )
            bar.setPadding(
                side + left,
                dp(ctx, 12f),
                side + right,
                dp(ctx, 12f) + bottom,
            )
            insets
        }

        // The column has to end above the bar, and how tall the bar is depends
        // on the font size, the navigation mode, and whether anything in it is
        // hidden. Measuring it is the only honest answer; a guessed number is
        // right on exactly one phone.
        wrap.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
            val want = (b - t) + dp(v.context, 16f)
            if (column.paddingBottom != want) {
                column.setPadding(
                    column.paddingLeft,
                    column.paddingTop,
                    column.paddingRight,
                    want,
                )
            }
        }
    }

    /** Twenty points on a phone; whatever keeps a line readable above that. */
    private fun sideMargin(ctx: Context, width: Int): Int {
        val base = dp(ctx, 20f)
        if (width <= 0) return base
        val extra = (width - dp(ctx, MAX_CONTENT)) / 2
        return if (extra > base) extra else base
    }

    fun column(ctx: Context): LinearLayout {
        val v = LinearLayout(ctx)
        v.orientation = LinearLayout.VERTICAL
        return v
    }

    fun strip(ctx: Context): LinearLayout {
        val v = LinearLayout(ctx)
        v.orientation = LinearLayout.HORIZONTAL
        return v
    }

    /**
     * The name of the screen, and one line saying where you are.
     *
     * Every activity runs under Theme.Material.NoActionBar, so the manifest's
     * android:label and every `title = ...` in these files has been setting a
     * string nothing draws.
     */
    fun header(ctx: Context, text: String, under: String? = null): LinearLayout {
        val box = column(ctx)
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_TITLE)
        t.typeface = Typeface.DEFAULT_BOLD
        box.addView(t)
        if (under != null) box.addView(note(ctx, under), row(ctx, 2f))
        return box
    }

    // ─── panels ─────────────────────────────────────────────────────────────

    fun card(ctx: Context): LinearLayout {
        val box = column(ctx)
        box.setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 14f))
        box.background = shape(ctx, CARD, LINE, RADIUS)
        return box
    }

    /**
     * A panel that holds rows rather than text.
     *
     * WHY THE ROWS ARE NOT NINE SEPARATE CARDS
     *
     * They were, and nine floating panels with air between them is not a list.
     * It is nine things that happen to be stacked. A list is one object with
     * lines in it, and reading down one costs far less than reading down nine.
     *
     * clipToOutline is what lets the rows be plain rectangles: the panel's
     * rounded corner does the cutting, so a press at the top of the first row
     * does not spill its ripple past the curve.
     */
    fun listCard(ctx: Context): LinearLayout {
        val box = column(ctx)
        box.background = shape(ctx, CARD, LINE, RADIUS)
        box.clipToOutline = true
        return box
    }

    /**
     * The name of a group of rows, with anything they all share said once.
     *
     * The right-hand half is why this is not simply a label. When every alarm
     * under a heading was moved for the same reason, the reason belongs to the
     * heading: four rows each carrying "เลื่อนจากรอบเงียบ" is one fact printed
     * four times.
     */
    fun sectionHead(ctx: Context, title: String, note: String? = null): LinearLayout {
        val box = strip(ctx)
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(ctx, 4f), 0, dp(ctx, 4f), dp(ctx, 8f))
        box.addView(
            label(ctx, title),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (note != null) {
            val n = label(ctx, note)
            n.gravity = Gravity.END
            box.addView(n)
        }
        return box
    }

    private fun shape(ctx: Context, fill: Int, stroke: Int?, radius: Float): GradientDrawable {
        val s = GradientDrawable()
        s.setColor(fill)
        s.cornerRadius = dp(ctx, radius).toFloat()
        if (stroke != null) s.setStroke(dp(ctx, 1f), stroke)
        return s
    }

    // ─── text ───────────────────────────────────────────────────────────────

    fun label(ctx: Context, text: String): TextView {
        val v = TextView(ctx)
        v.text = text
        v.setTextColor(FAINT)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_LABEL)
        v.letterSpacing = 0.08f
        return v
    }

    fun fieldLabel(ctx: Context, text: String): TextView {
        val v = TextView(ctx)
        v.text = text
        v.setTextColor(TEXT)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_NOTE)
        v.typeface = Typeface.DEFAULT_BOLD
        v.letterSpacing = 0.02f
        return v
    }

    fun body(ctx: Context, text: String = ""): TextView {
        val v = TextView(ctx)
        v.text = text
        v.setTextColor(TEXT)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_BODY)
        // Thai stacks vowels above and tone marks above those. The default line
        // height leaves the two rows touching. This is not a taste.
        v.setLineSpacing(dp(ctx, 3f).toFloat(), 1.1f)
        return v
    }

    fun note(ctx: Context, text: String = ""): TextView {
        val v = body(ctx, text)
        v.setTextColor(DIM)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_NOTE)
        return v
    }

    /** What went wrong with what was typed, and nothing when nothing did. */
    fun status(ctx: Context): TextView {
        val v = body(ctx, "")
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_NOTE)
        v.visibility = View.GONE
        v.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                v.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        })
        return v
    }

    /** Fixed width, for things read as columns rather than as prose. */
    fun mono(ctx: Context, text: String = ""): TextView {
        val v = body(ctx, text)
        v.typeface = Typeface.MONOSPACE
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_MONO)
        v.setTextColor(DIM)
        return v
    }

    // ─── things to press ────────────────────────────────────────────────────

    fun primary(ctx: Context, text: String, onClick: () -> Unit): Button =
        button(ctx, text, PRIMARY, ON_PRIMARY, null, true, onClick)

    fun secondary(ctx: Context, text: String, onClick: () -> Unit): Button =
        button(ctx, text, CARD, TEXT, LINE, false, onClick)

    fun danger(ctx: Context, text: String, onClick: () -> Unit): Button =
        button(ctx, text, fade(DANGER, 26), DANGER, fade(DANGER, 110), false, onClick)

    fun quiet(ctx: Context, text: String, onClick: () -> Unit): Button =
        button(ctx, text, Color.TRANSPARENT, DIM, null, false, onClick)

    fun chip(ctx: Context, text: String, onClick: () -> Unit): Button {
        val b = button(ctx, text, CARD, DIM, LINE, false, onClick)
        b.setPadding(dp(ctx, 10f), dp(ctx, 10f), dp(ctx, 10f), dp(ctx, 10f))
        b.minHeight = dp(ctx, 44f)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_NOTE)
        select(ctx, b, false)
        return b
    }

    /** Which chip is the chosen one, said in fill and ink rather than in fog. */
    fun select(ctx: Context, b: Button, on: Boolean) {
        val fill = if (on) fade(PRIMARY, 56) else CARD
        val edge = if (on) fade(ACCENT, 150) else LINE
        val ink = if (on) TEXT else DIM
        b.background = pressable(ctx, fill, edge, RADIUS_SMALL)
        b.setTextColor(inkStates(ink))
        b.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.alpha = 1f
    }

    /**
     * A theme, shown in its own colours.
     *
     * A picker that draws every option in the theme you are already using is a
     * picker that shows you six words. This one paints each option out of the
     * palette it would apply, so the choice is visible before it is made.
     */
    fun swatch(ctx: Context, p: Palette, on: Boolean, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = if (on) "✓ " + p.name else p.name
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_NOTE)
        b.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.setTextColor(inkStates(if (on) p.text else p.dim))
        b.setPadding(dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f))
        b.minHeight = dp(ctx, 44f)
        b.minimumHeight = dp(ctx, 44f)
        b.stateListAnimator = null
        b.gravity = Gravity.CENTER
        b.background = pressable(
            ctx,
            if (on) p.raised else p.card,
            if (on) p.accent else p.line,
            RADIUS_SMALL,
        )
        b.setOnClickListener { onClick() }
        return b
    }

    private fun button(
        ctx: Context,
        text: String,
        fill: Int,
        ink: Int,
        stroke: Int?,
        bold: Boolean,
        onClick: () -> Unit,
    ): Button {
        val b = Button(ctx)
        b.text = text
        b.setTextColor(inkStates(ink))
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_BODY)
        b.isAllCaps = false
        b.letterSpacing = 0.01f
        if (bold) b.typeface = Typeface.DEFAULT_BOLD
        b.setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
        b.minHeight = dp(ctx, TAP)
        b.minimumHeight = dp(ctx, TAP)
        b.gravity = Gravity.CENTER
        b.stateListAnimator = null
        b.background = pressable(ctx, fill, stroke, RADIUS_SMALL)
        b.setOnClickListener { onClick() }
        return b
    }

    /**
     * A background that knows it can be disabled and that it can be pressed.
     *
     * Every form writes `saveButton.isEnabled = ...` and always did. What it
     * did not have was any way for that to be visible. Doing it in the drawable
     * rather than at the call sites makes it true everywhere, including in
     * screens written later by somebody who never read this file.
     */
    private fun pressable(ctx: Context, fill: Int, stroke: Int?, radius: Float): RippleDrawable {
        val states = StateListDrawable()
        states.addState(
            intArrayOf(-android.R.attr.state_enabled),
            shape(ctx, fade(fill, Color.alpha(fill) / 3), stroke?.let { fade(it, 60) }, radius),
        )
        states.addState(intArrayOf(), shape(ctx, fill, stroke, radius))

        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, radius).toFloat()
        return RippleDrawable(ColorStateList.valueOf(fade(ACCENT, 90)), states, mask)
    }

    private fun inkStates(ink: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(fade(ink, 90), ink),
    )

    // ─── somewhere to type ──────────────────────────────────────────────────

    fun field(ctx: Context, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(ctx)
        e.hint = hint
        // Passed through rather than worked out here. Each form has its own rule
        // for which keyboard a box wants, and they are not the same rule; this
        // file decides what a field looks like, not what it takes.
        e.inputType = inputType
        e.setTextColor(TEXT)
        e.setHintTextColor(FAINT)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_BODY)
        e.highlightColor = fade(PRIMARY, 120)
        e.isSingleLine = true

        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            shape(ctx, FIELD, ACCENT, RADIUS_SMALL),
        )
        states.addState(intArrayOf(), shape(ctx, FIELD, LINE, RADIUS_SMALL))
        e.background = states
        // After the background: setting one resets padding to the drawable's.
        e.setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        e.minHeight = dp(ctx, TAP)
        e.minimumHeight = dp(ctx, TAP)
        return e
    }

    // ─── rows ───────────────────────────────────────────────────────────────

    /**
     * One task, as one object, inside a [listCard].
     *
     * FOUR COLUMNS AND WHY
     *
     *   rule    three points of colour, on the one row that is the answer
     *   mark    the tick
     *   text    the name, and a second line only when there is one to have
     *   clock   right-aligned and monospace, so times line up down the list
     *
     * The clock having a column of its own is what stopped the repetition. Four
     * tasks firing at 08:00 used to be four sentences each containing 08:00;
     * they are now four rows and one column of identical numbers, which the eye
     * reads once and skips.
     */
    class Row(
        val view: LinearLayout,
        val rule: View,
        val mark: TextView,
        val name: TextView,
        val note: TextView,
        val clock: TextView,
    )

    fun taskRow(ctx: Context, onClick: () -> Unit, onLong: () -> Unit): Row {
        val box = strip(ctx)
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(ctx, 12f), dp(ctx, 11f), dp(ctx, 14f), dp(ctx, 11f))
        box.background = pressable(ctx, Color.TRANSPARENT, null, 0f)
        box.isClickable = true
        box.minimumHeight = dp(ctx, TAP)
        box.setOnClickListener { onClick() }
        box.setOnLongClickListener { onLong(); true }

        // Always present, even while showing nothing. A rule that appears and
        // disappears moves every other row's text sideways by the width of
        // itself, which reads as the list twitching.
        val rule = View(ctx)
        val ruleParams = LinearLayout.LayoutParams(
            dp(ctx, 3f),
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        ruleParams.rightMargin = dp(ctx, 9f)
        box.addView(rule, ruleParams)

        val mark = TextView(ctx)
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        mark.gravity = Gravity.CENTER
        mark.minWidth = dp(ctx, 24f)

        val text = column(ctx)
        val name = TextView(ctx)
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_ROW)
        name.setLineSpacing(dp(ctx, 2f).toFloat(), 1.05f)
        val note = note(ctx, "")
        note.visibility = View.GONE
        text.addView(name)
        text.addView(note, row(ctx, 2f))

        val clock = TextView(ctx)
        clock.typeface = Typeface.MONOSPACE
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_CLOCK)
        clock.gravity = Gravity.END

        box.addView(mark)
        val textParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        textParams.leftMargin = dp(ctx, 10f)
        box.addView(text, textParams)
        val clockParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        clockParams.leftMargin = dp(ctx, 10f)
        box.addView(clock, clockParams)

        return Row(box, rule, mark, name, note, clock)
    }

    /**
     * How a row is drawn once it knows what it is.
     *
     * [lead] is the soonest thing not yet done — the answer to why the app was
     * opened. Exactly one row on the screen has it, and it is the only reason
     * this screen has a focal point rather than an inventory.
     */
    fun dressRow(
        ctx: Context,
        r: Row,
        done: Boolean,
        lead: Boolean,
        clock: String,
        note: String,
    ) {
        val marked = lead && !done
        r.mark.text = if (done) "✓" else "○"
        r.mark.setTextColor(if (done) POSITIVE else if (marked) ACCENT else FAINT)
        r.rule.setBackgroundColor(if (marked) PRIMARY else Color.TRANSPARENT)
        r.name.setTextColor(if (done) DIM else TEXT)
        r.name.typeface = if (marked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        r.view.background =
            pressable(ctx, if (marked) fade(PRIMARY, 30) else Color.TRANSPARENT, null, 0f)
        r.view.setPadding(dp(ctx, 12f), dp(ctx, 11f), dp(ctx, 14f), dp(ctx, 11f))

        r.clock.text = clock
        r.clock.setTextColor(if (done) FAINT else if (marked) ACCENT else DIM)
        r.clock.visibility = if (clock.isEmpty()) View.GONE else View.VISIBLE

        r.note.text = note
        r.note.setTextColor(if (marked) ACCENT else FAINT)
        r.note.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The line between two rows inside a [listCard]. */
    fun hairline(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(LINE)
        return v
    }

    fun hairlineRow(ctx: Context): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 1f),
        )
        // Inset so the line starts where the writing does, which is what makes
        // it read as a separator rather than as the edge of another box.
        p.leftMargin = dp(ctx, 12f)
        return p
    }

    /** A way to somewhere else: a name, a line about it, and a mark saying so. */
    fun navRow(ctx: Context, text: String, under: String, onClick: () -> Unit): LinearLayout {
        val box = strip(ctx)
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        box.background = pressable(ctx, Color.TRANSPARENT, null, 0f)
        box.isClickable = true
        box.minimumHeight = dp(ctx, TAP)
        box.setOnClickListener { onClick() }

        val stack = column(ctx)
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_BODY)
        stack.addView(t)
        if (under.isNotEmpty()) stack.addView(note(ctx, under), row(ctx, 2f))

        val chevron = TextView(ctx)
        chevron.text = "›"
        chevron.setTextColor(FAINT)
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)

        box.addView(stack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(chevron)
        return box
    }

    /**
     * Something the phone is doing that will stop a reminder arriving.
     *
     * A rule down the left rather than a filled box. The desktop learned this
     * one the hard way: a bordered panel with a heading, a body and a button
     * inside a card that already had five things in it doubled the height of
     * the card and read as something stuffed in rather than as something the
     * card was saying.
     */
    fun banner(
        ctx: Context,
        text: String,
        tone: Int,
        action: String? = null,
        onAction: (() -> Unit)? = null,
    ): LinearLayout {
        val box = strip(ctx)
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = shape(ctx, fade(tone, 22), null, RADIUS_SMALL)
        box.setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 10f), dp(ctx, 10f))

        val rule = View(ctx)
        rule.background = shape(ctx, tone, null, 2f)
        val ruleParams = LinearLayout.LayoutParams(dp(ctx, 3f), ViewGroup.LayoutParams.MATCH_PARENT)
        ruleParams.rightMargin = dp(ctx, 12f)
        box.addView(rule, ruleParams)

        val t = note(ctx, text)
        t.setTextColor(TEXT)
        box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (action != null && onAction != null) {
            val b = quiet(ctx, action, onAction)
            b.setTextColor(inkStates(tone))
            b.typeface = Typeface.DEFAULT_BOLD
            b.setPadding(dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 8f))
            b.minHeight = dp(ctx, 40f)
            b.minimumHeight = dp(ctx, 40f)
            box.addView(b)
        }
        return box
    }

    // ─── spacing ────────────────────────────────────────────────────────────

    fun row(ctx: Context, top: Float = 12f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        p.topMargin = dp(ctx, top)
        return p
    }

    /** One of several things side by side, sharing the width, with a gap. */
    fun cell(ctx: Context, first: Boolean = false, gap: Float = 8f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (!first) p.leftMargin = dp(ctx, gap)
        return p
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(LINE)
        return v
    }

    fun dividerRow(ctx: Context): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f))
        p.topMargin = dp(ctx, 28f)
        p.bottomMargin = dp(ctx, 4f)
        return p
    }

    fun show(v: View, on: Boolean) {
        v.visibility = if (on) View.VISIBLE else View.GONE
    }
}