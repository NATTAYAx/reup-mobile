// app/src/main/java/app/reup/ReupWidget.kt
package app.reup

import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.reup.core.Palette
import app.reup.core.WidgetFace
import app.reup.core.WidgetLine
import app.reup.core.nextPerTask
import app.reup.core.paletteOf
import app.reup.core.widgetFace
import app.reup.core.widgetMoneyLine
import app.reup.core.widgetNextChange
import app.reup.core.widgetTally
import app.reup.sync.CURRENCY_FALLBACK
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The widget on the home screen: the time, what is next, the rest of the day,
 * and what is still open further out. Looked at, not used - a tap anywhere
 * opens the app on the Today tab, and the little wallet opens the money tab.
 *
 * ─── HOW LITTLE IT COSTS ────────────────────────────────────────────────────
 *
 * Nothing runs to keep it up to date. The clock is the system's own and ticks
 * itself. Everything else is drawn when it changes - the app's own work asks
 * for a redraw after a tick, a sync, an alarm, a slip, a notice - and at the
 * one moment something on it would go stale by itself (widgetNextChange), with
 * an alarm that does not wake the phone: it is delivered the next time the
 * screen comes on, which is the next time anybody could see the widget.
 *
 * The light that moves across it is drawn by the home screen, from a list of
 * frames in the APK: about a second of movement every eight, and nothing
 * while the home screen is not on show. Off in Settings, and off whenever the
 * phone's own animations are.
 *
 * ─── THREE SIZES ────────────────────────────────────────────────────────────
 *
 * Two cells square, four by two, four by four - and in between, whichever the
 * size the person dragged it to is closest to. From Android 12 the home
 * screen is handed one layout per size it may show, cut for exactly that
 * size, and picks between them itself as the widget is resized.
 */
class ReupWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REDRAW,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> finishLater(TAG) { draw(context) }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        finishLater(TAG) { draw(context) }
    }

    /** Resized: the layouts are cut for the sizes it can now be. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        finishLater(TAG) { draw(context) }
    }

    /** The last one is gone; nothing needs drawing at midnight any more. */
    override fun onDisabled(context: Context) {
        cancelRedraw(context)
    }

    private enum class Kind(val layout: Int) {
        SMALL(R.layout.widget_small),
        MEDIUM(R.layout.widget_medium),
        LARGE(R.layout.widget_large),
    }

    /** What every size is drawn from, read once per redraw. */
    private class Drawn(
        val face: WidgetFace,
        val palette: Palette,
        val motion: Boolean,
        val money: String?,
        val waiting: Int,
        val picture: Long,
    )

    /** The colours words are written in: the theme's on a plain card, near-white on a picture. */
    private class Ink(val text: Int, val dim: Int, val accent: Int, val warn: Int, val positive: Int)

    companion object {
        private const val TAG = "ReupWidget"

        const val ACTION_REDRAW = "app.reup.widget.REDRAW"

        private const val REQ_REDRAW = 7_200
        private const val REQ_TODAY = 7_201
        private const val REQ_MONEY = 7_202

        /** How late a redraw may be when this phone may not set alarms to the minute. */
        private const val WINDOW_MS = 10 * 60_000L

        /** The longest side of the picture handed to the home screen, which keeps a copy of it. */
        private const val PICTURE_LONGEST = 1280f

        /** The card's corners on a home screen that does not round them: res/values/dimens.xml. */
        private const val CORNER_DP = 20f

        /** Opened from the widget, on this tab. MainActivity reads it back. */
        const val OPEN_TODAY = "today"
        const val OPEN_MONEY = "money"

        /**
         * Something the widget shows has changed. Sent to the receiver rather
         * than drawn here: the callers are a scheduler, a notification listener
         * and a form, none of which should wait for a picture to be cut, or
         * fail because one could not be. Nothing is sent when there is no
         * widget.
         */
        fun redraw(ctx: Context) {
            try {
                if (!placed(ctx)) return
                ctx.sendBroadcast(Intent(ctx, ReupWidget::class.java).setAction(ACTION_REDRAW))
            } catch (e: Exception) {
                Log.e(TAG, "could not ask for a redraw: " + e.javaClass.simpleName)
            }
        }

        /** How many are on the home screen. */
        fun count(ctx: Context): Int =
            try {
                AppWidgetManager.getInstance(ctx)?.getAppWidgetIds(ComponentName(ctx, ReupWidget::class.java))?.size ?: 0
            } catch (e: Exception) {
                0
            }

        private fun placed(ctx: Context): Boolean = count(ctx) > 0

        /**
         * Ask the home screen to put one on it. False when it cannot be asked,
         * and the person has to add it the usual way: hold the home screen,
         * widgets, Reup.
         */
        fun pin(ctx: Context): Boolean {
            val manager = AppWidgetManager.getInstance(ctx) ?: return false
            return try {
                manager.isRequestPinAppWidgetSupported &&
                    manager.requestPinAppWidget(ComponentName(ctx, ReupWidget::class.java), null, null)
            } catch (e: Exception) {
                false
            }
        }

        // ── drawing ─────────────────────────────────────────────────────────

        private val drawing = Mutex()

        @Volatile
        private var again = false

        /**
         * Every widget, drawn now. Asked for in bursts - a sync moves rows and
         * slips and the theme at once - so a redraw asked for while one is
         * running is folded into one more after it, never run alongside.
         */
        suspend fun draw(ctx: Context) {
            while (true) {
                if (!drawing.tryLock()) {
                    again = true
                    // Taken: the one running will see [again]. Unless it let go
                    // in the moment between, in which case this one draws.
                    if (drawing.isLocked) return
                    continue
                }
                try {
                    again = false
                    drawOnce(ctx)
                } finally {
                    drawing.unlock()
                }
                if (!again) return
            }
        }

        private suspend fun drawOnce(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, ReupWidget::class.java))
            if (ids.isEmpty()) {
                cancelRedraw(ctx)
                return
            }
            val now = Clock.System.now()
            val zone = TimeZone.currentSystemDefault()
            val repo = Repo.open(ctx)
            val tasks = repo.tasks()
            val infos = repo.infos()
            val quiet = Repo.quietHours(ctx)
            val rows = homeRows(tasks, infos, nextPerTask(tasks, now, zone, quiet), now, zone)
            val look = WidgetLook.read(ctx)
            val drawn = Drawn(
                face = widgetFace(rows, now, zone, quiet),
                palette = paletteOf(Ui.themeId(ctx)),
                // The phone's own "remove animations" turns this off too: it
                // is the one switch somebody who needs things still has
                // already found.
                motion = look.motion && ValueAnimator.areAnimatorsEnabled(),
                money = if (look.money) moneyLine(ctx, now, zone) else null,
                waiting = PendingSlips.all(ctx).size + BankNotices.waiting(ctx).size,
                picture = look.picture,
            )
            for (id in ids) {
                try {
                    manager.updateAppWidget(id, viewsFor(ctx, manager.getAppWidgetOptions(id), drawn))
                } catch (e: Exception) {
                    // One widget that could not be drawn is not a reason to
                    // leave the others as they were.
                    Log.e(TAG, "could not draw a widget: " + e.javaClass.simpleName)
                }
            }
            scheduleRedraw(ctx, widgetNextChange(rows, now, zone, quiet))
        }

        private suspend fun moneyLine(ctx: Context, now: Instant, zone: TimeZone): String? =
            try {
                val repo = Repo.money(ctx)
                val unit = repo.currency() ?: CURRENCY_FALLBACK
                val date = now.toLocalDateTime(zone).date
                val month = repo.summary(date.toString().take(7), unit)
                widgetMoneyLine(month.spent, month.received, unit, date.monthNumber)
            } catch (e: Exception) {
                null
            }

        // ── sizes ───────────────────────────────────────────────────────────

        /**
         * One layout per size the home screen says it may show, from Android
         * 12; before that, one, for the size it has upright.
         */
        private fun viewsFor(ctx: Context, options: Bundle?, d: Drawn): RemoteViews {
            // From Android 12 the card clips its own corners, and takes a layout per size.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = sizesOf(options)
                if (sizes.isNotEmpty()) {
                    val picture = picture(ctx, d.picture, sizes.maxOf { it.width }, sizes.maxOf { it.height }, rounded = false)
                    return RemoteViews(sizes.associateWith { build(ctx, it.width, it.height, d, picture) })
                }
            }
            val upright = uprightOf(options)
            val picture = picture(ctx, d.picture, upright.width, upright.height, rounded = Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return build(ctx, upright.width, upright.height, d, picture)
        }

        /** At most sixteen, which is as many as one update may carry. */
        private fun sizesOf(options: Bundle?): List<SizeF> {
            if (options == null) return emptyList()
            val list: List<SizeF>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            } else {
                @Suppress("DEPRECATION")
                options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            }
            return list.orEmpty().filter { it.width > 0f && it.height > 0f }.distinct().take(16)
        }

        /**
         * The size upright, in dp: the narrow width and the tall height. Four
         * by two when the home screen has not said yet, which it has not, the
         * first time a new widget is drawn.
         */
        private fun uprightOf(options: Bundle?): SizeF {
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            return if (w > 0 && h > 0) SizeF(w.toFloat(), h.toFloat()) else SizeF(300f, 170f)
        }

        /** The kept picture, cut for the largest of the sizes. Every size shares the one copy. */
        private fun picture(ctx: Context, version: Long, wDp: Float, hDp: Float, rounded: Boolean): Bitmap? {
            if (version == 0L) return null
            val density = ctx.resources.displayMetrics.density
            var w = wDp * density
            var h = hDp * density
            val scale = min(1f, PICTURE_LONGEST / max(w, h))
            w *= scale
            h *= scale
            val corner = if (rounded) CORNER_DP * density * scale else 0f
            return try {
                WidgetLook.pictureFor(ctx, version, w.roundToInt(), h.roundToInt(), corner)
            } catch (e: Exception) {
                null
            }
        }

        private fun kindOf(wDp: Float, hDp: Float): Kind = when {
            hDp >= 280f -> Kind.LARGE
            wDp >= 250f -> Kind.MEDIUM
            else -> Kind.SMALL
        }

        // ── one layout ──────────────────────────────────────────────────────

        private fun build(ctx: Context, wDp: Float, hDp: Float, d: Drawn, picture: Bitmap?): RemoteViews {
            val kind = kindOf(wDp, hDp)
            val v = RemoteViews(ctx.packageName, kind.layout)
            val p = d.palette
            val ink = if (picture != null) {
                Ink(text = WHITE, dim = WHITE_DIM, accent = p.accent, warn = p.warn, positive = p.positive)
            } else {
                Ink(text = p.text, dim = p.dim, accent = p.accent, warn = p.warn, positive = p.positive)
            }

            // Behind: the card in the theme's colour, the picture over it if
            // there is one, and the light over both.
            v.setInt(R.id.w_card, "setColorFilter", p.card)
            if (picture != null) {
                v.setImageViewBitmap(R.id.w_photo, picture)
                v.setViewVisibility(R.id.w_photo, View.VISIBLE)
                v.setViewVisibility(R.id.w_scrim, View.VISIBLE)
                v.setViewVisibility(R.id.w_glow, View.GONE)
                v.setViewVisibility(R.id.w_glow_end, View.GONE)
                // A pane that darkens rather than lightens: see widget_panel_dark.xml.
                v.setInt(R.id.w_head, "setBackgroundResource", R.drawable.widget_panel_dark)
            } else {
                v.setViewVisibility(R.id.w_photo, View.GONE)
                v.setViewVisibility(R.id.w_scrim, View.GONE)
                v.setViewVisibility(R.id.w_glow, View.VISIBLE)
                v.setViewVisibility(R.id.w_glow_end, View.VISIBLE)
                v.setInt(R.id.w_glow, "setColorFilter", p.accent)
                v.setInt(R.id.w_glow_end, "setColorFilter", p.primary)
                v.setInt(R.id.w_head, "setBackgroundResource", R.drawable.widget_panel)
            }
            v.setViewVisibility(R.id.w_shine, if (d.motion) View.VISIBLE else View.GONE)

            v.setTextColor(R.id.w_clock, ink.text)
            v.setTextViewText(R.id.w_date, if (kind == Kind.SMALL) d.face.dateShort else d.face.date)
            v.setTextColor(R.id.w_date, ink.dim)

            v.setViewVisibility(R.id.w_moon, if (d.face.quiet) View.VISIBLE else View.GONE)
            v.setInt(R.id.w_moon, "setColorFilter", ink.dim)

            if (d.waiting > 0) {
                v.setViewVisibility(R.id.w_wait, View.VISIBLE)
                v.setTextViewText(R.id.w_wait_count, if (kind == Kind.LARGE) "รอบันทึก ${d.waiting}" else d.waiting.toString())
                v.setTextColor(R.id.w_wait_count, ink.accent)
                v.setInt(R.id.w_wait_icon, "setColorFilter", ink.accent)
                v.setContentDescription(R.id.w_wait, "รอบันทึก ${d.waiting} รายการ")
                v.setOnClickPendingIntent(R.id.w_wait, open(ctx, OPEN_MONEY, REQ_MONEY))
            } else {
                v.setViewVisibility(R.id.w_wait, View.GONE)
            }

            head(v, kind, d.face, ink)
            progress(v, kind, d.face, ink)

            // The heights below are the layouts' own, in dp, with text lines at
            // about one and a half times their size, which is what Thai needs.
            val fs = ctx.resources.configuration.fontScale
            when (kind) {
                Kind.SMALL -> {
                    // A second line for a long name, when the height has room for one.
                    val room = hDp - (32f + 88.5f * fs)
                    v.setInt(R.id.w_head_name, "setMaxLines", if (room >= 2 * 21f * fs) 2 else 1)
                }
                Kind.MEDIUM -> {
                    // Under the card, in the right-hand column.
                    val room = hDp - (54f + 40.25f * fs)
                    lines(ctx, v, R.id.w_soon, d.face.soon.take(min(3, fit(room, rowDp(fs)))), ink)
                }
                Kind.LARGE -> large(ctx, v, hDp, fs, d, ink)
            }

            v.setOnClickPendingIntent(android.R.id.background, open(ctx, OPEN_TODAY, REQ_TODAY))
            return v
        }

        /** The card: what is next, or the day said in words when nothing is. */
        private fun head(v: RemoteViews, kind: Kind, face: WidgetFace, ink: Ink) {
            val next = face.next
            if (next != null) {
                v.setTextViewText(R.id.w_head_label, "ถัดไป")
                v.setTextColor(R.id.w_head_label, ink.accent)
                v.setTextViewText(R.id.w_head_name, next.name)
                v.setTextColor(R.id.w_head_name, ink.text)
                v.setTextViewText(R.id.w_head_time, next.time)
                v.setTextColor(R.id.w_head_time, if (next.warn) ink.warn else ink.accent)
                v.setViewVisibility(R.id.w_head_time, View.VISIBLE)
                return
            }
            v.setTextViewText(R.id.w_head_label, face.calm ?: "")
            v.setTextColor(R.id.w_head_label, if (face.done > 0) ink.positive else ink.dim)
            val after = face.calmNext
            when {
                after == null -> {
                    v.setTextViewText(R.id.w_head_name, "ไม่มีรอบถัดไป")
                    v.setViewVisibility(R.id.w_head_time, View.GONE)
                }
                // Two cells wide has no room for the time beside the words.
                kind == Kind.SMALL -> {
                    v.setTextViewText(R.id.w_head_name, after.time + " · " + after.name)
                    v.setViewVisibility(R.id.w_head_time, View.GONE)
                }
                else -> {
                    v.setTextViewText(R.id.w_head_name, after.name)
                    v.setTextViewText(R.id.w_head_time, after.time)
                    v.setTextColor(R.id.w_head_time, ink.dim)
                    v.setViewVisibility(R.id.w_head_time, View.VISIBLE)
                }
            }
            v.setTextColor(R.id.w_head_name, ink.dim)
        }

        /**
         * The bar: ticked today out of what the day holds. Not drawn on a day
         * with nothing in it, and only the words until something is ticked -
         * an empty bar beside "0 of 7" scores an evening before it happens.
         */
        private fun progress(v: RemoteViews, kind: Kind, face: WidgetFace, ink: Ink) {
            val words = widgetTally(face.done, face.load)
            if (words.isEmpty()) {
                v.setViewVisibility(R.id.w_progress, View.GONE)
                return
            }
            v.setViewVisibility(R.id.w_progress, View.VISIBLE)
            val ticked = face.done > 0
            v.setViewVisibility(R.id.w_bar, if (ticked) View.VISIBLE else View.GONE)
            if (ticked) {
                v.setInt(R.id.w_bar_fill, "setImageLevel", 10_000 * face.done / face.load)
                v.setInt(R.id.w_bar_fill, "setColorFilter", if (face.done >= face.load) ink.positive else ink.accent)
            }
            v.setTextViewText(
                R.id.w_count,
                when (kind) {
                    Kind.SMALL -> words[0]
                    Kind.MEDIUM -> words[1]
                    Kind.LARGE -> words[2]
                },
            )
            v.setTextColor(R.id.w_count, ink.dim)
        }

        /**
         * Four by four: what is due within the day, then what is open further
         * out, as many of each as fit - the day first, since it is sooner.
         */
        private fun large(ctx: Context, v: RemoteViews, hDp: Float, fs: Float, d: Drawn, ink: Ink) {
            val face = d.face
            val row = rowDp(fs)
            // Everything above and below the lists, at this font size.
            var room = hDp - (84f + 128f * fs)
            if (d.money != null) room -= 6f + 18.6f * fs
            // The heading over what is further out; only worth it for a row.
            val heading = 8f + 17f * fs

            var soon = min(face.soon.size, min(3, fit(room, row)))
            room -= soon * row
            var later = 0
            if (face.later.isNotEmpty() && room >= heading + row) {
                later = min(face.later.size, fit(room - heading, row))
                room -= heading + later * row
            }
            // Whatever is left goes back to the day.
            soon += min(face.soon.size - soon, fit(room, row))

            lines(ctx, v, R.id.w_soon, face.soon.take(soon), ink)
            if (later > 0) {
                v.setViewVisibility(R.id.w_later_label, View.VISIBLE)
                v.setTextColor(R.id.w_later_label, ink.dim)
                lines(ctx, v, R.id.w_later, face.later.take(later), ink)
            } else {
                v.setViewVisibility(R.id.w_later_label, View.GONE)
                lines(ctx, v, R.id.w_later, emptyList(), ink)
            }

            if (d.money != null) {
                v.setViewVisibility(R.id.w_money, View.VISIBLE)
                v.setTextViewText(R.id.w_money, d.money)
                v.setTextColor(R.id.w_money, ink.dim)
                v.setOnClickPendingIntent(R.id.w_money, open(ctx, OPEN_MONEY, REQ_MONEY))
            } else {
                v.setViewVisibility(R.id.w_money, View.GONE)
            }
        }

        /** One row of res/layout/widget_line.xml, in dp: 3dp above a line of 13sp. */
        private fun rowDp(fs: Float): Float = 3f + 20.2f * fs

        /** How many of [each] fit in [room]. */
        private fun fit(room: Float, each: Float): Int = if (room <= 0f) 0 else floor(room / each).toInt()

        /**
         * Rows into a column, replacing what was there. Cleared first because
         * the home screen may apply this to the views it already has, and adding
         * to those would add the same rows again under the old ones.
         */
        private fun lines(ctx: Context, v: RemoteViews, container: Int, lines: List<WidgetLine>, ink: Ink) {
            v.removeAllViews(container)
            v.setViewVisibility(container, if (lines.isEmpty()) View.GONE else View.VISIBLE)
            for (line in lines) {
                val row = RemoteViews(ctx.packageName, R.layout.widget_line)
                row.setTextViewText(R.id.w_line_name, line.name)
                row.setTextColor(R.id.w_line_name, ink.text)
                row.setTextViewText(R.id.w_line_time, line.time)
                row.setTextColor(R.id.w_line_time, if (line.warn) ink.warn else ink.dim)
                v.addView(container, row)
            }
        }

        /** The app, on a tab. NEW_TASK because a widget is not an activity; the rest so it is the app already open. */
        private fun open(ctx: Context, tab: String, request: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                request,
                Intent(ctx, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("reup://widget/$tab"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The tab a widget's tap asked for, or null for an intent that is not one. */
        fun tabOf(intent: Intent?): String? {
            val data = intent?.data ?: return null
            if (data.scheme != "reup" || data.host != "widget") return null
            return data.lastPathSegment?.takeIf { it == OPEN_TODAY || it == OPEN_MONEY }
        }

        // ── the next redraw ─────────────────────────────────────────────────

        /**
         * An alarm that does not wake the phone. Delivered at [at] if the
         * screen is on, or the moment it next comes on, which is the first
         * moment the widget could be seen being wrong.
         *
         * To the minute where this phone allows alarms to be, as it does for
         * the reminders; otherwise within ten minutes, which a date line can
         * afford to be late by and a reminder cannot.
         */
        private fun scheduleRedraw(ctx: Context, at: Instant) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pending = redrawIntent(ctx)
            val ms = at.toEpochMilliseconds()
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    am.setExact(AlarmManager.RTC, ms, pending)
                } else {
                    am.setWindow(AlarmManager.RTC, ms, WINDOW_MS, pending)
                }
            } catch (e: SecurityException) {
                // Taken away between the question and the call.
                am.setWindow(AlarmManager.RTC, ms, WINDOW_MS, pending)
            }
        }

        private fun cancelRedraw(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(redrawIntent(ctx))
        }

        private fun redrawIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx,
                REQ_REDRAW,
                Intent(ctx, ReupWidget::class.java).setAction(ACTION_REDRAW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Words on a picture: white, and white a little quieter, over the scrim. */
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val WHITE_DIM = 0xD9FFFFFF.toInt()
    }
}
