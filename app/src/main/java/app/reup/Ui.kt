// app/src/main/java/app/reup/Ui.kt
package app.reup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One place that decides what the phone looks like.
 *
 * ─── WHY THIS EXISTS AT ALL ─────────────────────────────────────────────────
 *
 * Every screen here was built to answer a question — does the alarm fire, does
 * the row arrive, does the recogniser read Thai — and each one got whatever
 * widget was quickest to type. The result is five screens that each invented
 * their own greys, their own spacing and their own idea of how big text is, and
 * a home screen that is ten identical grey slabs stacked on top of each other.
 *
 * ─── THE FAULT IS NOT THE COLOURS ───────────────────────────────────────────
 *
 * Ten identical buttons means no button is the answer to "what did I open this
 * for". Two of them are pressed most days: writing down money, and ticking a
 * task off. Four are pressed once ever, at setup — the test notification, the
 * battery page, the exact-alarm permission, the sync settings — and they are
 * drawn exactly as loudly as the two.
 *
 * So the sizes here are not decoration. A screen where everything is equally
 * prominent is a screen that has not been asked what it is for.
 *
 * ─── AND THE PIXELS WERE PIXELS ─────────────────────────────────────────────
 *
 * setPadding(48, 64, 48, 64) is forty-eight PIXELS, not points. On a dense
 * phone that is about nineteen points and on a cheap one it is forty-eight, so
 * the same code produced two different layouts and neither was the one anybody
 * chose. Everything here goes through [dp].
 *
 * ─── WHY IT MATCHES THE DESKTOP ─────────────────────────────────────────────
 *
 * Same background and same violet as theme.ts. They are one app used by one
 * person on two machines, and two products that look unrelated is a thing that
 * gets noticed every single time both are open.
 */
object Ui {

    // Straight from the desktop palette. Changing one of these should mean
    // changing it there too — see src/lib/theme.ts.
    val BG = Color.parseColor("#030712")
    val CARD = Color.parseColor("#111827")
    val LINE = Color.parseColor("#1F2937")
    val PRIMARY = Color.parseColor("#7C3AED")
    val ACCENT = Color.parseColor("#A78BFA")

    val TEXT = Color.parseColor("#E8E8EA")
    val DIM = Color.parseColor("#9CA3AF")
    val FAINT = Color.parseColor("#6B7280")

    /** Points to pixels, on whatever screen this is running on. */
    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            ctx.resources.displayMetrics,
        ).toInt()

    /**
     * The outer shell every screen shares: a scrolling column on the app's
     * background, with room down the sides.
     *
     * Returns the column. The caller adds to it and passes [scroll] to
     * setContentView, which is two objects rather than one because a screen
     * that needs to scroll to the bottom after adding something has to be able
     * to reach the ScrollView.
     */
    class Screen(val scroll: ScrollView, val column: LinearLayout)

    fun screen(ctx: Context): Screen {
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(ctx, 20f), dp(ctx, 24f), dp(ctx, 20f), dp(ctx, 40f))

        val scroll = ScrollView(ctx)
        scroll.setBackgroundColor(BG)
        scroll.isFillViewport = true
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
     * A block that belongs together, on a slightly lighter panel.
     *
     * Used for the live readout rather than for everything: a screen where each
     * item is in its own box is a screen with no grouping at all, which is the
     * same problem as no boxes wearing a different coat.
     */
    fun card(ctx: Context): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 14f))
        val shape = GradientDrawable()
        shape.setColor(CARD)
        shape.cornerRadius = dp(ctx, 14f).toFloat()
        shape.setStroke(dp(ctx, 1f), LINE)
        box.background = shape
        return box
    }

    /** The name of a section. Small and quiet: it is a signpost, not news. */
    fun label(ctx: Context, text: String): TextView {
        val v = TextView(ctx)
        v.text = text
        v.setTextColor(FAINT)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        v.letterSpacing = 0.08f
        return v
    }

    /** Ordinary reading text. */
    fun body(ctx: Context, text: String = ""): TextView {
        val v = TextView(ctx)
        v.text = text
        v.setTextColor(TEXT)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        v.setLineSpacing(dp(ctx, 4f).toFloat(), 1f)
        return v
    }

    /** The same, said more quietly. For anything that is context, not content. */
    fun note(ctx: Context, text: String = ""): TextView {
        val v = body(ctx, text)
        v.setTextColor(DIM)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        return v
    }

    /**
     * Fixed-width text, for things that are read as columns.
     *
     * Numbers and times line up under each other, which is the whole reason the
     * queue readout was monospace to begin with. Kept, but only where that is
     * actually true — prose in a monospace font reads like a machine talking.
     */
    fun mono(ctx: Context, text: String = ""): TextView {
        val v = body(ctx, text)
        v.typeface = Typeface.MONOSPACE
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        return v
    }

    /**
     * The thing this screen is for. There should be one or two, never six.
     */
    fun primary(ctx: Context, text: String, onClick: () -> Unit): Button =
        button(ctx, text, PRIMARY, Color.WHITE, onClick)

    /**
     * Everything else. Same size to press, far less to look at.
     *
     * Same height as a primary on purpose: quieter is about attention, and a
     * button that is harder to hit is a different thing from a button that is
     * less important.
     */
    fun secondary(ctx: Context, text: String, onClick: () -> Unit): Button =
        button(ctx, text, CARD, TEXT, onClick, LINE)

    private fun button(
        ctx: Context,
        text: String,
        fill: Int,
        ink: Int,
        onClick: () -> Unit,
        stroke: Int? = null,
    ): Button {
        val b = Button(ctx)
        b.text = text
        b.setTextColor(ink)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.isAllCaps = false
        b.setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
        b.minHeight = dp(ctx, 48f)
        b.gravity = Gravity.CENTER
        b.stateListAnimator = null

        val shape = GradientDrawable()
        shape.setColor(fill)
        shape.cornerRadius = dp(ctx, 12f).toFloat()
        if (stroke != null) shape.setStroke(dp(ctx, 1f), stroke)

        // A press that shows nothing is a press somebody repeats. The mask is
        // the same shape, so the ripple stays inside the rounded corners.
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, 12f).toFloat()
        b.background = RippleDrawable(ColorStateList.valueOf(ACCENT), shape, mask)

        b.setOnClickListener { onClick() }
        return b
    }

    /** Full width, with air above. [top] is in points. */
    fun row(ctx: Context, top: Float = 12f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        p.topMargin = dp(ctx, top)
        return p
    }

    /** A hairline, for where one group of things stops being the same group. */
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
}