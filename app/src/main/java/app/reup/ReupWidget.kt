// app/src/main/java/app/reup/ReupWidget.kt
package app.reup

import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.reup.core.FILM_BUDGET_SHARES
import app.reup.core.INK_DARK
import app.reup.core.Note
import app.reup.core.Palette
import app.reup.core.WARN_AMBER
import app.reup.core.WidgetFace
import app.reup.core.WidgetGroup
import app.reup.core.WidgetInk
import app.reup.core.WidgetItem
import app.reup.core.WidgetSchedule
import app.reup.core.InkMode
import app.reup.core.filmBudget
import app.reup.core.mixRgb
import app.reup.core.nextPerTask
import app.reup.core.noteLook
import app.reup.core.notesShown
import app.reup.core.paletteOf
import app.reup.core.widgetFace
import app.reup.core.widgetInk
import app.reup.core.widgetItems
import app.reup.core.widgetMoneyLine
import app.reup.core.widgetNextChange
import app.reup.core.widgetSchedule
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
 * The widget on the home screen: the time, the notes stuck on it, what is next,
 * the rest of the day, and what is still open further out. A tap anywhere opens
 * the app on the Today tab; the little wallet opens the money tab; a note opens
 * to be changed, the round button on its corner takes it off, and the + sticks
 * up a new one.
 *
 * ─── HOW LITTLE IT COSTS ────────────────────────────────────────────────────
 *
 * Nothing runs to keep it up to date. The clock is the system's own and ticks
 * itself. Everything else is drawn when it changes - the app's own work asks
 * for a redraw after a tick, a sync, an alarm, a slip, a notice, a note - and
 * at the one moment something on it would go stale by itself
 * (widgetNextChange), with an alarm that does not wake the phone: it is
 * delivered the next time the screen comes on, which is the next time anybody
 * could see the widget.
 *
 * What moves on it is turned by the home screen, never by this app. With a
 * film kept (WidgetFilm), a flipbook of it; without one, a band of light from a
 * list of frames in the APK, about a second of movement every eight. Either
 * stops while the home screen is not on show, and both are off in Settings,
 * and whenever the phone's own animations are. Set clear, it has no background
 * at all, and what moves is the wallpaper behind it (ReupWallpaper).
 *
 * ─── HOW IT READS ───────────────────────────────────────────────────────────
 *
 * The way the desktop's board reads, since that is the one that does: no boxes,
 * one thing large - what is next, in bold, with its time under it - and then
 * one list with the time in its own column and the name after it, a heading
 * for each day, the bar, the longer rounds. Over a picture, the words are
 * white or dark and the veil under them is as thick as the picture needs and
 * no thicker (core/WidgetInk.kt). A note brings its own paper, so it reads on
 * anything; it is drawn as a picture because a widget can neither lean a view
 * nor load this app's font (NotePaper).
 *
 * ─── WHAT IT SAYS ───────────────────────────────────────────────────────────
 *
 * A line for each moment rather than for each round (widgetSchedule), so a
 * row of games that reset together takes one line, and a task marked private
 * in Settings is called ส่วนตัว. A line that cannot name every round it holds
 * names what fits and counts the rest: "FGO · Arknights +2".
 *
 * ─── THREE SIZES ────────────────────────────────────────────────────────────
 *
 * Two cells square, four by two, four by four - and in between, whichever the
 * size the person dragged it to is closest to. From Android 12 the home
 * screen is handed one layout per size it may show, cut for exactly that
 * size, and picks between them itself as the widget is resized; and the list
 * is a ListView, so it fills the height the home screen actually gives it
 * rather than one worked out beforehand.
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
        val schedule: WidgetSchedule,
        val palette: Palette,
        val motion: Boolean,
        val money: String?,
        val waiting: Int,
        val picture: Long,
        /** The film to play, when there is one and things may move; 0 otherwise. */
        val film: Long,
        /** No background of its own; the wallpaper shows through. */
        val clear: Boolean,
        /** The notes that are up, newest first. */
        val notes: List<Note>,
        /** How they look: core/Notes.kt's bright, muted or dark. */
        val noteStyle: String,
        /** The words' colours, and the veil under them when there is one. */
        val ink: Ink,
    )

    /**
     * The colours words are written in: the theme's on a plain card; over a
     * picture or the wallpaper, white or dark with a veil under them as thick
     * as the picture needs ([veil], 0 for none).
     */
    private class Ink(
        val text: Int,
        val dim: Int,
        val accent: Int,
        val warn: Int,
        val positive: Int,
        /** Dark words over a light veil, rather than white over a dark one. */
        val darkText: Boolean,
        val veil: Double,
        /** Under the bar's fill. */
        val track: Int,
    )

    /** What goes in each layout's note row: the papers cut for it, and how many more there are. */
    private class Papers(val shown: List<Pair<Note, Bitmap>>, val more: Int)

    companion object {
        private const val TAG = "ReupWidget"

        const val ACTION_REDRAW = "app.reup.widget.REDRAW"

        private const val REQ_REDRAW = 7_200
        private const val REQ_TODAY = 7_201
        private const val REQ_MONEY = 7_202
        private const val REQ_NOTE_EDIT = 7_204
        private const val REQ_NOTE_PEEL = 7_205
        private const val REQ_LIST = 7_206

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

        /** The share of the system's allowance a film was last drawn at; tried first. Kept for the process. */
        @Volatile
        private var filmShare: Double? = null

        /**
         * A film, by version, that the system refused at every share while it
         * took the still picture: drawn still from then on, for the process,
         * rather than cut twice more at every redraw only to be refused again.
         */
        @Volatile
        private var filmRefused: Long = 0L

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
            val rows = homeRows(tasks, infos, nextPerTask(tasks, now, zone, quiet), now, zone, Repo.dayStart(ctx))
            val look = WidgetLook.read(ctx)
            // Private tasks keep their place and their time, not their names.
            val named = if (look.hidden.isEmpty()) {
                rows
            } else {
                rows.map { if (it.id in look.hidden) it.copy(name = WidgetLook.HIDDEN_NAME) else it }
            }
            val face = widgetFace(named, now, zone, quiet)
            // The phone's own "remove animations" turns this off too: it is the
            // one switch somebody who needs things still has already found.
            val motion = look.motion && ValueAnimator.areAnimatorsEnabled()
            val palette = paletteOf(Ui.themeId(ctx))
            val notes = try {
                Repo.notes(ctx).live()
            } catch (e: Exception) {
                // A note that cannot be read costs the notes, not the widget.
                Log.e(TAG, "could not read the notes: " + e.javaClass.simpleName)
                emptyList()
            }
            // The light is the film's when there is one, whether or not it is
            // playing: a film shown still is its first frame, measured with it.
            val picture = if (look.clear) 0L else look.picture
            val light = if (picture != 0L) WidgetLook.light(ctx, if (look.film != 0L) look.film else look.picture) else null
            val drawn = Drawn(
                face = face,
                schedule = widgetSchedule(face),
                palette = palette,
                motion = motion,
                money = if (look.money) moneyLine(ctx, now, zone) else null,
                waiting = PendingSlips.all(ctx).size + BankNotices.waiting(ctx).size,
                picture = picture,
                film = if (motion && !look.clear) look.film else 0L,
                clear = look.clear,
                notes = notes,
                noteStyle = Ui.noteStyle(ctx),
                ink = inkFor(ctx, palette, look, picture != 0L, light),
            )
            // With a film: cut to nearly all the system allows first, then to
            // less if it refuses that - it measures the screen by its own
            // reckoning - and last of all the film's first frame, still. A
            // refusal changes nothing on the home screen. Without one, once.
            // The share that worked last time is tried first, so a phone that
            // refuses the larger one is not asked again at every redraw.
            val filming = drawn.film != 0L && drawn.film != filmRefused
            val shares: List<Double?> = if (filming) {
                FILM_BUDGET_SHARES.filter { it <= (filmShare ?: Double.MAX_VALUE) } + null
            } else {
                listOf(null)
            }
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                for (share in shares) {
                    try {
                        manager.updateAppWidget(id, viewsFor(ctx, options, drawn, share))
                        if (share != null) {
                            filmShare = share
                        } else if (filming) {
                            filmRefused = drawn.film
                        }
                        break
                    } catch (e: Exception) {
                        // One widget that could not be drawn is not a reason to
                        // leave the others as they were.
                        Log.e(TAG, "could not draw a widget" + (if (share != null) " with its film at $share" else "") + ": " + e.javaClass.simpleName)
                    }
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

        // ── the words' colours ──────────────────────────────────────────────

        /**
         * Over a picture, what core/WidgetInk.kt works out from how bright it
         * is. Clear, over the wallpaper, whichever way the wallpaper says it
         * wants words written - the same hint the home screen reads for its
         * own labels - under a thin veil, since nothing here has measured it.
         * On the plain card, the theme's own colours, which are readable by
         * construction (PaletteTest).
         */
        private fun inkFor(ctx: Context, p: Palette, look: WidgetLook.Look, hasPicture: Boolean, light: app.reup.core.PictureLight?): Ink {
            if (hasPicture && light != null) {
                val w = widgetInk(light, look.ink, rgb(p.accent))!!
                return fromCore(w, p)
            }
            if (hasPicture || look.clear) {
                val dark = when (look.ink) {
                    InkMode.LIGHT -> false
                    InkMode.DARK -> true
                    InkMode.AUTO -> look.clear && wallpaperWantsDarkText(ctx)
                }
                return veiled(dark, if (look.clear) 0.32 else 0.45, p)
            }
            return Ink(
                text = p.text,
                dim = p.dim,
                accent = p.accent,
                warn = p.warn,
                positive = p.positive,
                darkText = false,
                veil = 0.0,
                track = Color.argb(46, 255, 255, 255),
            )
        }

        private fun fromCore(w: WidgetInk, p: Palette): Ink =
            Ink(
                text = opaque(w.text),
                dim = withAlpha(opaque(w.text), if (w.darkText) 0.80 else 0.84),
                accent = opaque(w.accent),
                warn = opaque(w.warn),
                positive = if (w.darkText) opaque(mixRgb(rgb(p.positive), 0x000000, 0.35)) else p.positive,
                darkText = w.darkText,
                veil = w.veil,
                track = withAlpha(opaque(w.text), 0.22),
            )

        /** A picture not measured yet, or the wallpaper: one way round, under a veil of [veil]. */
        private fun veiled(dark: Boolean, veil: Double, p: Palette): Ink {
            val text = if (dark) opaque(INK_DARK) else Color.WHITE
            val toward = if (dark) 0x000000 else 0xFFFFFF
            return Ink(
                text = text,
                dim = withAlpha(text, if (dark) 0.80 else 0.84),
                accent = opaque(mixRgb(rgb(p.accent), toward, 0.45)),
                warn = opaque(mixRgb(WARN_AMBER, toward, if (dark) 0.45 else 0.2)),
                positive = p.positive,
                darkText = dark,
                veil = veil,
                track = withAlpha(text, 0.22),
            )
        }

        /** Whether the wallpaper asks for dark words, which only Android 12 says out loud. */
        private fun wallpaperWantsDarkText(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            return try {
                val colors = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            } catch (e: Exception) {
                false
            }
        }

        private fun rgb(color: Int): Int = color and 0xFFFFFF
        private fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)
        private fun withAlpha(color: Int, a: Double): Int = ((a * 255).roundToInt() shl 24) or (color and 0xFFFFFF)

        // ── sizes ───────────────────────────────────────────────────────────

        /**
         * One layout per size the home screen says it may show, from Android
         * 12; before that, one, for the size it has upright. [share] of the
         * system's allowance for the film's pictures; null for no film.
         */
        private fun viewsFor(ctx: Context, options: Bundle?, d: Drawn, share: Double?): RemoteViews {
            val dm = ctx.resources.displayMetrics
            // From Android 12 the card clips its own corners, and takes a layout per size.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = sizesOf(options)
                if (sizes.isNotEmpty()) {
                    // The notes first: what their papers weigh comes off what
                    // the film may, since one allowance covers every picture.
                    val cut = HashMap<String, Bitmap?>()
                    val papers = sizes.associateWith { papersFor(ctx, d.notes, d.noteStyle, kindOf(it.width, it.height), it.width, it.height, cut) }
                    val paperBytes = cut.values.filterNotNull().sumOf { it.allocationByteCount.toLong() }
                    val wDp = sizes.maxOf { it.width }
                    val hDp = sizes.maxOf { it.height }
                    // One cut of the film for every size, like the picture: the
                    // same pictures in each layout are sent once.
                    val frames = if (share != null) filmFor(ctx, d.film, wDp, hDp, share, paperBytes) else null
                    val picture = if (frames != null) null else picture(ctx, d.picture, wDp, hDp, rounded = false)
                    return RemoteViews(sizes.associateWith { build(ctx, it.width, it.height, d, picture, frames, papers.getValue(it)) })
                }
            }
            val upright = uprightOf(options)
            val cut = HashMap<String, Bitmap?>()
            val papers = papersFor(ctx, d.notes, d.noteStyle, kindOf(upright.width, upright.height), upright.width, upright.height, cut)
            val paperBytes = cut.values.filterNotNull().sumOf { it.allocationByteCount.toLong() }
            val frames = if (share != null) filmFor(ctx, d.film, upright.width, upright.height, share, paperBytes) else null
            val picture = if (frames != null) null else picture(ctx, d.picture, upright.width, upright.height, rounded = Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return build(ctx, upright.width, upright.height, d, picture, frames, papers)
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

        /**
         * The kept film, cut for the largest of the sizes, within what the
         * system lets one widget's pictures add up to on this screen less what
         * the notes' papers already take. Null for none, or none that fits,
         * which draws the picture instead.
         */
        private fun filmFor(ctx: Context, version: Long, wDp: Float, hDp: Float, share: Double, taken: Long): WidgetFilm.Film? {
            if (version == 0L) return null
            val dm = ctx.resources.displayMetrics
            return try {
                WidgetFilm.framesFor(
                    ctx,
                    version,
                    (wDp * dm.density).roundToInt(),
                    (hDp * dm.density).roundToInt(),
                    max(0L, filmBudget(dm.widthPixels, dm.heightPixels, share) - taken),
                )
            } catch (e: Exception) {
                Log.e(TAG, "could not cut the film: " + e.javaClass.simpleName)
                null
            } catch (e: OutOfMemoryError) {
                // Thirty-odd pictures at once, in a receiver: the picture
                // instead, rather than no widget and a stopped app.
                Log.e(TAG, "no memory to cut the film")
                null
            }
        }

        private fun kindOf(wDp: Float, hDp: Float): Kind = when {
            hDp >= 280f -> Kind.LARGE
            wDp >= 250f -> Kind.MEDIUM
            else -> Kind.SMALL
        }

        // ── the notes ───────────────────────────────────────────────────────

        /**
         * How many notes a layout of [kind] has room for, and the paper each
         * is drawn on, in dp. Four by four takes three across; four by two one
         * or two in its left column; two square, the newest one alone.
         */
        private fun noteRoom(kind: Kind, wDp: Float, hDp: Float): Triple<Int, Float, Float> {
            val m = NotePaper.MARGIN_DP
            return when (kind) {
                Kind.LARGE -> {
                    val across = wDp - 34f + m
                    val w = min(132f, across / 3f - 2 * m - 4f)
                    Triple(3, w, min(96f, w * 0.72f))
                }
                Kind.MEDIUM -> {
                    val column = (wDp - 30f - 12f) / 2.25f + m
                    val n = if (column >= 2 * (96f + 2 * m)) 2 else 1
                    val w = min(150f, column / n - 2 * m - 4f)
                    val h = min(w * 0.68f, hDp - 26f - 54f - 22f - 2 * m)
                    Triple(n, w, h)
                }
                Kind.SMALL -> {
                    val w = min(190f, wDp - 24f + m - 2 * m)
                    val h = min(w * 0.66f, hDp - 20f - 42f - 20f - 2 * m)
                    Triple(1, w, h)
                }
            }
        }

        /**
         * The notes a layout shows, each cut once per paper size and shared by
         * every layout that wants the same size - [cut] carries them from one
         * layout to the next and is what the film's allowance is reduced by.
         */
        private fun papersFor(ctx: Context, notes: List<Note>, style: String, kind: Kind, wDp: Float, hDp: Float, cut: HashMap<String, Bitmap?>): Papers {
            if (notes.isEmpty()) return Papers(emptyList(), 0)
            val (room, wd, hd) = noteRoom(kind, wDp, hDp)
            if (wd < 56f || hd < 36f) return Papers(emptyList(), notes.size)
            val (shown, more) = notesShown(notes, room)
            val d = ctx.resources.displayMetrics.density
            val w = (wd * d).roundToInt()
            val h = (hd * d).roundToInt()
            val out = ArrayList<Pair<Note, Bitmap>>()
            for (n in shown) {
                val key = "${n.uid}:${n.body.hashCode()}:${n.color}:$style:$w:$h"
                val bmp = cut.getOrPut(key) {
                    NotePaper.draw(ctx, n, style, w, h)?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.asShared() else it }
                }
                if (bmp != null) out.add(n to bmp)
            }
            return Papers(out, more + (shown.size - out.size))
        }

        private fun notesInto(ctx: Context, v: RemoteViews, papers: Papers, d: Drawn) {
            v.removeAllViews(R.id.w_notes)
            if (papers.shown.isEmpty()) {
                v.setViewVisibility(R.id.w_notes, View.GONE)
                return
            }
            v.setViewVisibility(R.id.w_notes, View.VISIBLE)
            for ((note, paper) in papers.shown) {
                val slot = RemoteViews(ctx.packageName, R.layout.widget_note)
                slot.setImageViewBitmap(R.id.w_note_paper, paper)
                slot.setContentDescription(R.id.w_note_paper, "โน้ต: " + note.body)
                // The button in the paper's own ink, on white or, for dark paper, on dark.
                slot.setInt(R.id.w_note_tick, "setColorFilter", NotePaper.ink(d.noteStyle, note.color))
                slot.setInt(
                    R.id.w_note_tick,
                    "setBackgroundResource",
                    if (noteLook(d.noteStyle, note.color).dark) R.drawable.widget_tick_dark else R.drawable.widget_tick,
                )
                slot.setOnClickPendingIntent(R.id.w_note_paper, noteIntent(ctx, note.uid))
                slot.setOnClickPendingIntent(R.id.w_note_tick, peelIntent(ctx, note.uid))
                slot.setContentDescription(R.id.w_note_tick, "แกะออก: " + note.body)
                v.addView(R.id.w_notes, slot)
            }
            if (papers.more > 0) {
                val more = RemoteViews(ctx.packageName, R.layout.widget_more)
                more.setTextViewText(R.id.w_more, "+" + papers.more)
                more.setTextColor(R.id.w_more, d.ink.text)
                more.setInt(R.id.w_more, "setBackgroundResource", if (d.ink.darkText) R.drawable.widget_pill_dark else R.drawable.widget_pill)
                more.setContentDescription(R.id.w_more, "โน้ตอีก ${papers.more} แผ่น")
                more.setOnClickPendingIntent(R.id.w_more, open(ctx, OPEN_TODAY, REQ_TODAY))
                v.addView(R.id.w_notes, more)
            }
        }

        // ── one layout ──────────────────────────────────────────────────────

        private fun build(ctx: Context, wDp: Float, hDp: Float, d: Drawn, picture: Bitmap?, film: WidgetFilm.Film?, papers: Papers): RemoteViews {
            val kind = kindOf(wDp, hDp)
            val v = RemoteViews(ctx.packageName, kind.layout)
            val p = d.palette
            val ink = d.ink

            // Behind: the card in the theme's colour, the picture or the film
            // over it if there is one, and the veil over both. Clear, none of
            // it: a veil, and the wallpaper through it.
            v.setViewVisibility(R.id.w_card, if (d.clear) View.GONE else View.VISIBLE)
            v.setInt(R.id.w_card, "setColorFilter", p.card)
            // The flipper is a stub until the first film, which brings it in;
            // after that the stub is gone and these find the flipper itself.
            // Emptied every time, since the home screen may apply this to the
            // views it already has, and a film from before would play on under
            // a picture. With no flipper yet, all of it finds nothing and does
            // nothing.
            if (film != null) v.setViewVisibility(R.id.w_film_stub, View.VISIBLE)
            v.removeAllViews(R.id.w_film)
            if (film != null) {
                for (frame in film.frames) {
                    val page = RemoteViews(ctx.packageName, R.layout.widget_frame)
                    page.setImageViewBitmap(R.id.w_frame, frame)
                    v.addView(R.id.w_film, page)
                }
                // The film's own pace: twenty-four pages a second, or twelve.
                v.setInt(R.id.w_film, "setFlipInterval", film.tickMs.toInt())
            }
            v.setViewVisibility(R.id.w_film, if (film != null) View.VISIBLE else View.GONE)
            if (picture != null) v.setImageViewBitmap(R.id.w_photo, picture)
            v.setViewVisibility(R.id.w_photo, if (picture != null) View.VISIBLE else View.GONE)
            val plain = picture == null && film == null && !d.clear
            v.setViewVisibility(R.id.w_glow, if (plain) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.w_glow_end, if (plain) View.VISIBLE else View.GONE)
            if (plain) {
                v.setInt(R.id.w_glow, "setColorFilter", p.accent)
                v.setInt(R.id.w_glow_end, "setColorFilter", p.primary)
            }
            if (!plain && ink.veil > 0.0) {
                v.setViewVisibility(R.id.w_veil, View.VISIBLE)
                v.setInt(R.id.w_veil, "setColorFilter", if (ink.darkText) Color.WHITE else Color.BLACK)
                v.setInt(R.id.w_veil, "setImageAlpha", (ink.veil * 255).roundToInt().coerceIn(0, 255))
            } else {
                v.setViewVisibility(R.id.w_veil, View.GONE)
            }
            // The light is for a card with nothing else moving on it; with no
            // card it would cross the wallpaper instead.
            v.setViewVisibility(R.id.w_shine, if (d.motion && film == null && !d.clear) View.VISIBLE else View.GONE)

            v.setTextColor(R.id.w_clock, ink.text)
            v.setTextViewText(R.id.w_date, if (kind == Kind.SMALL) d.face.dateShort else d.face.date)
            v.setTextColor(R.id.w_date, ink.dim)

            v.setViewVisibility(R.id.w_moon, if (d.face.quiet) View.VISIBLE else View.GONE)
            v.setInt(R.id.w_moon, "setColorFilter", ink.dim)

            if (d.waiting > 0) {
                v.setViewVisibility(R.id.w_wait, View.VISIBLE)
                v.setTextViewText(R.id.w_wait_count, if (kind == Kind.LARGE) "รอบันทึก ${d.waiting}" else d.waiting.toString())
                v.setTextColor(R.id.w_wait_count, ink.text)
                v.setInt(R.id.w_wait_icon, "setColorFilter", ink.text)
                v.setInt(R.id.w_wait, "setBackgroundResource", if (ink.darkText) R.drawable.widget_pill_dark else R.drawable.widget_pill)
                v.setContentDescription(R.id.w_wait, "รอบันทึก ${d.waiting} รายการ")
                v.setOnClickPendingIntent(R.id.w_wait, open(ctx, OPEN_MONEY, REQ_MONEY))
            } else {
                v.setViewVisibility(R.id.w_wait, View.GONE)
            }

            if (kind != Kind.SMALL) {
                v.setInt(R.id.w_add, "setColorFilter", ink.text)
                v.setInt(R.id.w_add, "setBackgroundResource", if (ink.darkText) R.drawable.widget_add_dark else R.drawable.widget_add_light)
                v.setOnClickPendingIntent(R.id.w_add, noteIntent(ctx, null))
            }

            notesInto(ctx, v, papers, d)
            val noted = papers.shown.isNotEmpty()

            val fs = ctx.resources.configuration.fontScale
            val cols = Columns(ctx, kind, wDp)
            // Two cells square: a note takes the room the card would have had.
            val headShown = !(kind == Kind.SMALL && noted)
            v.setViewVisibility(R.id.w_head, if (headShown) View.VISIBLE else View.GONE)
            if (headShown) head(v, d.face, d.schedule.head, ink, cols)

            when (kind) {
                Kind.SMALL -> progress(v, kind, d.face, ink, shown = !noted)
                Kind.MEDIUM -> {
                    progress(v, kind, d.face, ink, shown = !noted)
                    list(ctx, v, kind, widgetItems(d.face, null, null), ink, cols, wDp, hDp, fs, noted)
                }
                Kind.LARGE -> {
                    val words = widgetTally(d.face.done, d.face.load)
                    list(ctx, v, kind, widgetItems(d.face, words.getOrNull(2), d.money), ink, cols, wDp, hDp, fs, noted)
                }
            }

            v.setOnClickPendingIntent(android.R.id.background, open(ctx, OPEN_TODAY, REQ_TODAY))
            return v
        }

        /** The card: what is next - everything due at that moment - or the day said in words when nothing is. */
        private fun head(v: RemoteViews, face: WidgetFace, next: WidgetGroup?, ink: Ink, cols: Columns) {
            if (next != null) {
                v.setTextViewText(R.id.w_head_label, "ถัดไป")
                v.setTextColor(R.id.w_head_label, ink.accent)
                v.setTextViewText(R.id.w_head_name, cols.headNames(next))
                v.setTextColor(R.id.w_head_name, ink.text)
                v.setTextViewText(R.id.w_head_time, next.time)
                v.setTextColor(R.id.w_head_time, if (next.warn) ink.warn else ink.text)
                v.setViewVisibility(R.id.w_head_time, View.VISIBLE)
                return
            }
            v.setTextViewText(R.id.w_head_label, face.calm ?: "")
            v.setTextColor(R.id.w_head_label, if (face.done > 0) ink.positive else ink.dim)
            val after = face.calmNext
            if (after == null) {
                v.setTextViewText(R.id.w_head_name, "ไม่มีรอบถัดไป")
                v.setViewVisibility(R.id.w_head_time, View.GONE)
            } else {
                v.setTextViewText(R.id.w_head_name, after.name)
                v.setTextViewText(R.id.w_head_time, after.time)
                v.setTextColor(R.id.w_head_time, ink.dim)
                v.setViewVisibility(R.id.w_head_time, View.VISIBLE)
            }
            v.setTextColor(R.id.w_head_name, ink.dim)
        }

        /**
         * The bar on its own, in the smaller two sizes: ticked today out of
         * what the day holds. Not drawn on a day with nothing in it, and only
         * the words until something is ticked - an empty bar beside "0 of 7"
         * scores an evening before it happens.
         */
        private fun progress(v: RemoteViews, kind: Kind, face: WidgetFace, ink: Ink, shown: Boolean) {
            val words = widgetTally(face.done, face.load)
            if (!shown || words.isEmpty()) {
                v.setViewVisibility(R.id.w_progress, View.GONE)
                return
            }
            v.setViewVisibility(R.id.w_progress, View.VISIBLE)
            val ticked = face.done > 0
            v.setViewVisibility(R.id.w_bar, if (ticked) View.VISIBLE else View.GONE)
            if (ticked) {
                v.setInt(R.id.w_bar_fill, "setImageLevel", 10_000 * face.done / face.load)
                v.setInt(R.id.w_bar_fill, "setColorFilter", if (face.done >= face.load) ink.positive else ink.accent)
                v.setInt(R.id.w_bar_track, "setColorFilter", ink.track)
            }
            v.setTextViewText(R.id.w_count, if (kind == Kind.SMALL) words[0] else words[1])
            v.setTextColor(R.id.w_count, ink.dim)
        }

        // ── the list ────────────────────────────────────────────────────────

        /**
         * The rows under the card. From Android 12 a ListView holding every
         * one of them, which shows as many as the real height has room for
         * and fades the last one out if it is cut; before that, as many as
         * the reported height is worked out to hold, added by hand.
         */
        private fun list(
            ctx: Context,
            v: RemoteViews,
            kind: Kind,
            items: List<WidgetItem>,
            ink: Ink,
            cols: Columns,
            wDp: Float,
            hDp: Float,
            fs: Float,
            noted: Boolean,
        ) {
            val times = TimeColumns(ctx, items)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setViewVisibility(R.id.w_rows, View.GONE)
                v.removeAllViews(R.id.w_rows)
                if (items.isEmpty()) {
                    v.setViewVisibility(R.id.w_list, View.GONE)
                    return
                }
                v.setViewVisibility(R.id.w_list, View.VISIBLE)
                val built = RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true)
                    .setViewTypeCount(4)
                for ((i, item) in items.withIndex()) {
                    val row = itemViews(ctx, item, ink, cols, times)
                    row.setOnClickFillInIntent(R.id.w_item, Intent())
                    built.addItem(stableId(i, item), row)
                }
                v.setRemoteAdapter(R.id.w_list, built.build())
                v.setPendingIntentTemplate(R.id.w_list, listTemplate(ctx))
                return
            }
            v.setViewVisibility(R.id.w_list, View.GONE)
            v.removeAllViews(R.id.w_rows)
            val fitting = items.take(roughFit(kind, items, wDp, hDp, fs, noted))
            v.setViewVisibility(R.id.w_rows, if (fitting.isEmpty()) View.GONE else View.VISIBLE)
            for (item in fitting) v.addView(R.id.w_rows, itemViews(ctx, item, ink, cols, times))
        }

        private fun itemViews(ctx: Context, item: WidgetItem, ink: Ink, cols: Columns, times: TimeColumns): RemoteViews =
            when (item) {
                is WidgetItem.Heading -> RemoteViews(ctx.packageName, R.layout.widget_item_heading).apply {
                    setTextViewText(R.id.w_item, item.text)
                    setTextColor(R.id.w_item, ink.dim)
                }
                is WidgetItem.Row -> RemoteViews(ctx.packageName, R.layout.widget_line).apply {
                    val width = times.width(item.later)
                    setTextViewText(R.id.w_line_time, item.time)
                    setTextColor(R.id.w_line_time, if (item.warn) ink.warn else ink.dim)
                    setInt(R.id.w_line_time, "setWidth", width)
                    setTextViewText(R.id.w_line_name, cols.rowNames(item.names, width))
                    setTextColor(R.id.w_line_name, ink.text)
                }
                is WidgetItem.Tally -> RemoteViews(ctx.packageName, R.layout.widget_item_tally).apply {
                    setTextViewText(R.id.w_tally_words, item.words)
                    setTextColor(R.id.w_tally_words, ink.dim)
                    val ticked = item.done > 0 && item.load > 0
                    setViewVisibility(R.id.w_tally_bar, if (ticked) View.VISIBLE else View.GONE)
                    if (ticked) {
                        setInt(R.id.w_tally_fill, "setImageLevel", 10_000 * item.done / item.load)
                        setInt(R.id.w_tally_fill, "setColorFilter", if (item.done >= item.load) ink.positive else ink.accent)
                        setInt(R.id.w_tally_track, "setColorFilter", ink.track)
                    }
                }
                is WidgetItem.Money -> RemoteViews(ctx.packageName, R.layout.widget_item_money).apply {
                    setTextViewText(R.id.w_item, item.text)
                    setTextColor(R.id.w_item, ink.dim)
                }
            }

        /** The same row keeps its id from one redraw to the next, so the list does not flicker. */
        private fun stableId(i: Int, item: WidgetItem): Long =
            (item.hashCode().toLong() shl 8) xor i.toLong()

        /**
         * Before Android 12 there is no list that sizes itself, so the rows are
         * counted against the height the home screen reports, at this font size,
         * with Thai lines at about one and a half times their size.
         */
        private fun roughFit(kind: Kind, items: List<WidgetItem>, wDp: Float, hDp: Float, fs: Float, noted: Boolean): Int {
            val noteH = if (noted) noteRoom(kind, wDp, hDp).third + 2 * NotePaper.MARGIN_DP + 6f else 0f
            var room = when (kind) {
                Kind.LARGE -> hDp - (24f + 54f * fs + 23f * fs + 8f + 18f * fs + 31f * fs + 24f * fs + 8f) - noteH
                Kind.MEDIUM -> hDp - (22f + 18f * fs + 27f * fs + 21f * fs + 4f)
                Kind.SMALL -> 0f
            }
            var n = 0
            for (item in items) {
                val h = when (item) {
                    is WidgetItem.Heading -> 8f + 18f * fs
                    is WidgetItem.Row -> 6f + 22.5f * fs
                    is WidgetItem.Tally -> 11f + 18f * fs
                    is WidgetItem.Money -> 10f + 19.5f * fs
                }
                if (room < h) break
                room -= h
                n++
            }
            return n
        }

        /**
         * The width of the time column for each of the two lists, measured the
         * way the home screen will draw the times: the longest in the list, and
         * a hair to spare. Each list measures its own, because "หมด อา. 18:00"
         * would push "09:00" a long way from its name.
         */
        private class TimeColumns(ctx: Context, items: List<WidgetItem>) {
            private val day: Int
            private val later: Int

            init {
                val dm = ctx.resources.displayMetrics
                val pen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, dm)
                    fontFeatureSettings = "tnum"
                }
                val least = (44f * dm.density).roundToInt()
                fun widest(rows: List<WidgetItem.Row>) =
                    max(least, (rows.maxOfOrNull { pen.measureText(it.time) } ?: 0f).let { (it + 3f * dm.density).roundToInt() })
                val rows = items.filterIsInstance<WidgetItem.Row>()
                day = widest(rows.filter { !it.later })
                later = widest(rows.filter { it.later })
            }

            fun width(isLater: Boolean): Int = if (isLater) later else day
        }

        /**
         * How wide the names are in a layout [wDp] wide, from the layouts' own
         * paddings, and the names of a line that fit there.
         *
         * Measured the way the home screen will draw them - the phone's font, at
         * the phone's text size - and with a tenth to spare, since the widths
         * are worked out rather than seen. Too wide a guess would end a line in
         * "…" and lose the count after it; too narrow costs one name.
         */
        private class Columns(ctx: Context, kind: Kind, wDp: Float) {
            private val dm = ctx.resources.displayMetrics
            private val headPen = pen(
                when (kind) {
                    Kind.SMALL -> 15f
                    Kind.MEDIUM -> 18f
                    Kind.LARGE -> 21f
                },
                bold = true,
            )
            private val rowPen = pen(15f, bold = false)
            /** The card's words and the list, in px. */
            private val headWidth: Float
            private val listWidth: Float

            init {
                val (head, list) = when (kind) {
                    Kind.SMALL -> (wDp - 24f) to (wDp - 24f)
                    // Padding 16 and 14, 12 between the columns, the right one 1.25 of 2.25.
                    Kind.MEDIUM -> ((wDp - 42f) * 1.25f / 2.25f).let { it to it }
                    Kind.LARGE -> (wDp - 34f) to (wDp - 34f)
                }
                headWidth = max(0f, head) * dm.density
                listWidth = max(0f, list) * dm.density
            }

            private fun pen(sp: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

            /** Up to two lines of the card's names. */
            fun headNames(g: WidgetGroup): String = fitNames(g.names, headPen, headWidth * 2 * 0.8f)

            /** A row's names beside a time column [timeWidth] px wide and the 12dp after it. */
            fun rowNames(names: List<String>, timeWidth: Int): String =
                fitNames(names, rowPen, (listWidth - timeWidth - 12f * dm.density) * 0.9f)
        }

        /**
         * As many of [names] as fit in [room] pixels at [pen], and how many did
         * not: "FGO · Arknights +2". The first is always named, cut short with
         * "…" if it alone is too long, so the count is never what is cut.
         */
        private fun fitNames(names: List<String>, pen: TextPaint, room: Float): String {
            if (names.size <= 1) return names.firstOrNull() ?: ""
            for (k in names.size downTo 1) {
                val shown = names.subList(0, k).joinToString(" · ")
                val text = if (k == names.size) shown else shown + " +" + (names.size - k)
                if (pen.measureText(text) <= room) return text
            }
            val more = " +" + (names.size - 1)
            val first = TextUtils.ellipsize(names[0], pen, max(0f, room - pen.measureText(more)), TextUtils.TruncateAt.END)
            return (first.toString() + more).trim()
        }

        // ── where taps go ───────────────────────────────────────────────────

        /** The app, on a tab. NEW_TASK because a widget is not an activity; the rest so it is the app already open. */
        private fun open(ctx: Context, tab: String, request: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                request,
                todayIntent(ctx, tab),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun todayIntent(ctx: Context, tab: String): Intent =
            Intent(ctx, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("reup://widget/$tab"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * The rows of the list. A template, because the rows of a ListView are
         * not views this code holds; mutable, because that is how the system
         * lets each row's fill-in reach it. The fill-ins are empty: every row
         * opens Today, and the template names the activity outright.
         */
        private fun listTemplate(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                REQ_LIST,
                todayIntent(ctx, OPEN_TODAY),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** A note to write, or the one with [uid] to change. Keyed by the uid in the data, so each note has its own. */
        private fun noteIntent(ctx: Context, uid: String?): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                REQ_NOTE_EDIT,
                Intent(ctx, AddNoteActivity::class.java)
                    .setData(Uri.parse("reup://note/" + (uid ?: "new")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The round button on a note's corner. */
        private fun peelIntent(ctx: Context, uid: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx,
                REQ_NOTE_PEEL,
                Intent(ctx, PeelReceiver::class.java).setData(Uri.parse("reup://note/$uid")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The tab a widget's tap asked for, or null for an intent that is not one. */
        fun tabOf(intent: Intent?): String? {
            val data = intent?.data ?: return null
            if (data.scheme != "reup" || data.host != "widget") return null
            return data.lastPathSegment?.takeIf { it == OPEN_TODAY || it == OPEN_MONEY }
        }

        /** The note a widget's tap named, or null for a new one or an intent that is not one. */
        fun noteOf(intent: Intent?): String? {
            val data = intent?.data ?: return null
            if (data.scheme != "reup" || data.host != "note") return null
            return data.lastPathSegment?.takeIf { it != "new" && it.isNotBlank() }
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
    }
}
