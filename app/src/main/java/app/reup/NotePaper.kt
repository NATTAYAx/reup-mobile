// app/src/main/java/app/reup/NotePaper.kt
package app.reup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import app.reup.core.Note
import app.reup.core.NOTE_LEAD
import app.reup.core.NoteLook
import app.reup.core.noteLook
import app.reup.core.noteTilt
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A note as the widget draws it: a square of paper, leaning a little, with a
 * shadow under it and the words on it in a hand.
 *
 * ─── WHY A PICTURE AND NOT A TEXT VIEW ──────────────────────────────────────
 *
 * Three things a widget's own views cannot do. It cannot lean - a widget may
 * not rotate a view. It cannot cast a soft shadow under a shape. And it cannot
 * write in this app's own font: the home screen builds a widget's views with a
 * context that is not allowed to load font files from another app, so a
 * TextView asked for one falls back to the phone's. Drawn here, in this app's
 * own process, all three are ordinary.
 *
 * The cost is the picture itself, which counts against what one widget's
 * pictures may add up to (see core/Film.kt). A note is drawn no larger than it
 * is shown, and ReupWidget takes the notes' share off the film's before it
 * cuts the film.
 *
 * ─── THE HAND ───────────────────────────────────────────────────────────────
 *
 * Mali, by Cadson Demak, under the SIL Open Font License (assets/licenses).
 * Rounded and looped the way a pen writes Thai, and still easy to read small,
 * which a marker-style face is not.
 *
 * ─── THE PAPER ──────────────────────────────────────────────────────────────
 *
 * In the style the person picked (core/Notes.kt): bright, muted or dark. Dark
 * paper on a dark home screen needs something to say where it ends, so it is
 * drawn with an edge and a strip of tape in its ink; the light ones have their
 * shadow, as a real note does.
 */
object NotePaper {

    private const val TAG = "NotePaper"

    /** Room around the paper for its lean and its shadow, in dp. */
    const val MARGIN_DP = 7f

    /** Inside the paper, in dp. */
    private const val PAD_DP = 10f

    /** The largest and smallest the words are written at, in sp. */
    private const val LARGEST_SP = 17f
    private const val SMALLEST_SP = 11f

    @Volatile
    private var hand: Typeface? = null

    /** The handwriting, loaded once; the phone's own face if the file will not load. */
    fun hand(ctx: Context): Typeface {
        hand?.let { return it }
        val face = try {
            ctx.resources.getFont(R.font.mali_medium)
        } catch (e: Exception) {
            Log.e(TAG, "the handwriting would not load: " + e.javaClass.simpleName)
            Typeface.DEFAULT
        }
        hand = face
        return face
    }

    /** A note's ink in [style], as a colour the canvas and the tick take. */
    fun ink(style: String, color: String): Int = opaque(noteLook(style, color).ink)

    private fun opaque(rgb: Int): Int = Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private fun withAlpha(rgb: Int, alpha: Int): Int = Color.argb(alpha, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    /**
     * [note] on a square of paper [paperW] by [paperH] pixels, in [style], with
     * [MARGIN_DP] around it; null if it cannot be drawn, which costs that one
     * note its place rather than the widget.
     */
    fun draw(ctx: Context, note: Note, style: String, paperW: Int, paperH: Int): Bitmap? {
        if (paperW <= 0 || paperH <= 0) return null
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val m = (MARGIN_DP * d).roundToInt()
        val look: NoteLook = noteLook(style, note.color)
        return try {
            val out = Bitmap.createBitmap(paperW + 2 * m, paperH + 2 * m, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paperRect = RectF(m.toFloat(), m.toFloat(), (m + paperW).toFloat(), (m + paperH).toFloat())
            c.rotate(noteTilt(note.uid), out.width / 2f, out.height / 2f)

            // The shadow: a little below and to the right, as if lit from above.
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(if (look.dark) 130 else 84, 0, 0, 0)
                maskFilter = BlurMaskFilter(3f * d, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(RectF(paperRect).apply { offset(1f * d, 2.5f * d) }, 2f * d, 2f * d, shadow)

            c.drawRoundRect(paperRect, 2f * d, 2f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque(look.paper) })
            // A shade toward the bottom edge, where a real note lifts off the door.
            val lift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, paperRect.top + paperH * 0.55f, 0f, paperRect.bottom,
                    Color.argb(0, 0, 0, 0), Color.argb(if (look.dark) 56 else 20, 0, 0, 0), Shader.TileMode.CLAMP,
                )
            }
            c.drawRoundRect(paperRect, 2f * d, 2f * d, lift)
            if (look.dark) {
                // Where it ends, and what holds it up.
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // Set by name: inside here, "style" is the parameter.
                    setStyle(Paint.Style.STROKE)
                    strokeWidth = 1f * d
                    color = withAlpha(look.ink, 77)
                }
                c.drawRoundRect(RectF(paperRect).apply { inset(0.5f * d, 0.5f * d) }, 2f * d, 2f * d, edge)
                // Capped, so a wide sheet's tape still clears the margin when both lean.
                val tapeW = minOf(paperW * 0.3f, 40f * d)
                val tapeH = 9f * d
                c.save()
                c.rotate(-3f, paperRect.centerX(), paperRect.top)
                c.drawRoundRect(
                    RectF(paperRect.centerX() - tapeW / 2, paperRect.top - tapeH / 2, paperRect.centerX() + tapeW / 2, paperRect.top + tapeH / 2),
                    1.5f * d, 1.5f * d,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(look.ink, 140) },
                )
                c.restore()
            }

            val pad = PAD_DP * d
            val inner = (paperW - 2 * pad).roundToInt()
            // The layout's top is the face's real top, where a tone mark on an
            // upper vowel reaches (see words), so it can start nearer the edge
            // than the words themselves do.
            val top = pad * 0.3f
            val layout = words(ctx, note.body, opaque(look.ink), inner, paperH - top - pad * 0.6f, dm)
            c.save()
            c.translate(paperRect.left + pad, paperRect.top + top)
            layout.draw(c)
            c.restore()
            out
        } catch (e: Exception) {
            Log.e(TAG, "could not draw a note: " + e.javaClass.simpleName)
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * The words, at the largest size from [LARGEST_SP] down that fits the
     * paper whole; at the smallest, as much as fits with "…" at the end.
     *
     * Mali's own line is 1.05 em up and 0.25 em down, and its Thai is taller:
     * a tone mark on an upper vowel reaches 1.45 em, ุ and ู hang to 0.41 em.
     * So the lines are [NOTE_LEAD] apart rather than the face's 1.3, which ran
     * one line's marks into the vowels under the one above, and the padding
     * is included, which takes the first line up to the face's real top and
     * the last down to its real bottom. The height checked is the layout's
     * own, padding and all, so nothing that fits is drawn past the paper.
     */
    private fun words(ctx: Context, text: String, ink: Int, width: Int, height: Float, dm: android.util.DisplayMetrics): StaticLayout {
        val pen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = hand(ctx)
            color = ink
        }
        var sp = LARGEST_SP
        while (true) {
            pen.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            // Added rather than multiplied: a multiplier would also stretch
            // the first line, whose height includes the padding.
            val pitch = NOTE_LEAD * pen.textSize
            val add = pitch - (pen.fontMetrics.descent - pen.fontMetrics.ascent)
            var lines = max(1, (height / pitch).toInt())
            var layout: StaticLayout
            while (true) {
                layout = StaticLayout.Builder.obtain(text, 0, text.length, pen, max(1, width))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(true)
                    .setLineSpacing(add, 1f)
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                if (layout.height <= height || lines == 1) break
                lines--
            }
            val cut = layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0
            if (!cut || sp <= SMALLEST_SP) return layout
            sp -= 1f
        }
    }
}
