// app/src/main/java/app/reup/SlipPicture.kt
package app.reup

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.reup.core.expenseLabel
import app.reup.core.paletteOf
import app.reup.sync.Category
import app.reup.sync.slipFace
import app.reup.ui.PrimaryButton
import app.reup.ui.Reup
import app.reup.ui.ReupTheme
import app.reup.ui.ReupType
import app.reup.ui.SecondaryButton
import app.reup.ui.Size
import app.reup.ui.TextAction
import app.reup.ui.reupColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The picture a waiting slip was read from, for looking at.
 *
 * ─── WHY THE PICTURE IS SHOWN AT ALL ────────────────────────────────────────
 *
 * A waiting slip's row says what the rules made of a picture — ค่าข้าว, −฿45 —
 * and a reading is exactly the thing that can be wrong: เทดสอบ101 was one. A
 * row that cannot be held up against the slip it came from asks to be trusted,
 * and a row nobody recognises leaves nothing to find out what it was by. So
 * every waiting slip shows its own picture: small at the head of its row,
 * whole on a screen of its own, and at the top of the form it opens in.
 *
 * ─── WHY IT IS NOT COPIED ───────────────────────────────────────────────────
 *
 * The picture stays where the bank's app saved it and is found again by its
 * gallery id, which is the id the slip is kept under already. A copy would be
 * a second set of somebody's bank slips inside this app, kept for nothing. A
 * picture deleted from the gallery leaves the slip with its reading and no
 * picture, and the screens say so rather than showing an empty frame.
 */
object SlipPictures {

    /** Where the slip with this gallery id lives. */
    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** Enough pixels for a 40 × 52 point tile on the densest screen there is. */
    private const val THUMB_WIDTH = 180
    private const val THUMB_HEIGHT = 240

    /**
     * Longest side of a picture opened whole. A slip saved by a bank's app is
     * under this and opens at its own size, which is what zooming in needs; a
     * camera photo of a paper receipt is halved until it fits.
     */
    private const val LONGEST = 3000

    /**
     * Thumbnails already made. Thirty slips wait at most, at about a hundred and
     * seventy kilobytes each, so this holds all of them and the list scrolls
     * without going back to the gallery.
     */
    private val thumbs = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /** A thumbnail made earlier, without touching the gallery. */
    fun cachedThumbnail(id: Long): Bitmap? = thumbs.get(id)

    /**
     * A small copy of the slip, or null when its picture cannot be opened: gone
     * from the gallery, or no permission to look at it any more.
     *
     * Blocking. Called off the main thread.
     */
    fun thumbnail(ctx: Context, id: Long): Bitmap? {
        thumbs.get(id)?.let { return it }
        val made = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.loadThumbnail(uri(id), android.util.Size(THUMB_WIDTH, THUMB_HEIGHT), null)
            } else {
                decode(ctx, id, THUMB_HEIGHT)
            }
        } catch (e: Exception) {
            null
        } ?: return null
        thumbs.put(id, made)
        return made
    }

    /** The whole slip, or null when it cannot be opened. Blocking. */
    fun full(ctx: Context, id: Long): Bitmap? = try {
        decode(ctx, id, LONGEST)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * The top of [picture], cut to [aspect] (width over height).
     *
     * A slip's top is the part that says what it is — the bank, ✓ โอนเงินสำเร็จ —
     * and a tile cut from its middle would be two lines of somebody's account
     * number. Compose can align a crop to the top by itself; an ImageView cannot.
     */
    fun top(picture: Bitmap, aspect: Float): Bitmap {
        val h = (picture.width / aspect).toInt().coerceIn(1, picture.height)
        return if (h == picture.height) picture else Bitmap.createBitmap(picture, 0, 0, picture.width, h)
    }

    private fun decode(ctx: Context, id: Long, longest: Int): Bitmap? {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Null is this call's answer every time: asked only for the size, it
        // fills in [bounds] and returns no bitmap. Taking that null for a
        // failure is what made every slip "เปิดรูปนี้ไม่ได้" in the first build
        // of this screen. Whether the picture could be opened is in the size.
        resolver.openInputStream(uri(id))?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longest) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri(id))?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}

/**
 * One slip's picture, the whole of it, with what was read off it underneath.
 *
 * Opened from the thumbnail on a waiting slip's row, where it also carries
 * that row's answers — save, edit, don't save — so the slip can be looked at
 * and settled in one place. Those answers go back to the money tab, which acts
 * on them exactly as if the row had been pressed: the same save, the same
 * "เอาคืน" after a slip is thrown away. Opened from the form, it is only the
 * picture; the form is where the answers are.
 *
 * Two fingers or a double tap to zoom, because the point of opening a slip is
 * usually one line on it.
 */
class SlipPictureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        // Read once. A slip saved from the shade while this is open is still
        // shown; its answers come back to a tab that no longer has it, and the
        // tab ignores them.
        val slip = if (id >= 0L) PendingSlips.get(this, id) else null
        val answers = intent.getBooleanExtra(EXTRA_ANSWERS, false) && slip != null
        val colors = reupColors(paletteOf(Ui.themeId(this)))
        setContent {
            ReupTheme(colors) {
                SlipPictureScreen(id = id, slip = slip, answers = answers, onClose = ::finish, onAnswer = ::answer)
            }
        }
    }

    private fun answer(what: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_ID, intent.getLongExtra(EXTRA_ID, -1L)).putExtra(EXTRA_ANSWER, what),
        )
        finish()
    }

    companion object {
        const val EXTRA_ID = "app.reup.picture_id"
        const val EXTRA_ANSWERS = "app.reup.picture_answers"
        const val EXTRA_ANSWER = "app.reup.picture_answer"

        const val SAVE = "save"
        const val EDIT = "edit"
        const val DISCARD = "discard"

        /** The picture of the slip under [id]; with [answers], the row's three answers too. */
        fun intent(ctx: Context, id: Long, answers: Boolean): Intent =
            Intent(ctx, SlipPictureActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_ANSWERS, answers)
    }
}

/** How far two fingers may zoom, and where a double tap goes. */
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

private sealed interface Shown {
    data object Loading : Shown
    data object Missing : Shown
    class Ready(val picture: ImageBitmap) : Shown
}

@Composable
private fun SlipPictureScreen(
    id: Long,
    slip: PendingSlip?,
    answers: Boolean,
    onClose: () -> Unit,
    onAnswer: (String) -> Unit,
) {
    val c = Reup.colors
    val ctx = LocalContext.current
    val shown by produceState<Shown>(Shown.Loading, id) {
        val picture = withContext(Dispatchers.IO) { SlipPictures.full(ctx, id) }
        value = if (picture == null) Shown.Missing else Shown.Ready(picture.asImageBitmap())
    }
    // The category's own name when somebody named it, as on the row.
    val categories by produceState<Map<String, Category>>(emptyMap()) {
        value = try {
            Repo.money(ctx).categories().associateBy { it.key }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Size.touch)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "ปิด", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "ปิด",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // Where it came from, and the one gesture nobody guesses.
            val from = slip?.album?.takeIf { it.isNotBlank() } ?: "รูปสลิป"
            Text(
                text = "$from · แตะสองครั้งเพื่อซูม",
                style = ReupType.support,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = shown) {
                Shown.Loading -> Words("กำลังเปิดรูป…")
                Shown.Missing -> Words("เปิดรูปนี้ไม่ได้ อาจถูกลบออกจากเครื่องไปแล้ว หรือแอปไม่มีสิทธิ์ดูรูป")
                is Shown.Ready -> Zoomable(s.picture, Modifier.fillMaxSize())
            }
        }

        if (slip != null) {
            val d = slip.draft
            val label = expenseLabel(d.category, d.category?.let { categories[it] }?.label)
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val face = slipFace(d, label, today)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.card)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = Size.gutter, end = Size.gutter, top = 14.dp, bottom = 12.dp),
            ) {
                Text(
                    text = face.title,
                    style = ReupType.rowTitle,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = face.text,
                    style = ReupType.support,
                    color = if (d.oneTap || d.toSelf) c.dim else c.warn,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (answers) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAction(text = "ไม่บันทึก", color = c.danger, onClick = { onAnswer(SlipPictureActivity.DISCARD) })
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(text = "แก้ไข", onClick = { onAnswer(SlipPictureActivity.EDIT) })
                        }
                        // Only where the row and the notification have it too.
                        if (d.oneTap) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                PrimaryButton(text = "บันทึก", onClick = { onAnswer(SlipPictureActivity.SAVE) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Words(text: String) {
    Text(
        text = text,
        style = ReupType.calmBody,
        color = Color.White.copy(alpha = 0.72f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 40.dp),
    )
}

/**
 * A picture that fits the screen, and zooms.
 *
 * Two fingers zoom about the point between them and drag it about; a double
 * tap goes to two and a half times at the point tapped, and back. The picture
 * cannot be dragged further than its own enlargement, so it never ends up off
 * to one side with nothing on the screen.
 */
@Composable
private fun Zoomable(picture: ImageBitmap, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val x = (s - 1f) * box.width / 2f
        val y = (s - 1f) * box.height / 2f
        return Offset(o.x.coerceIn(-x, x), o.y.coerceIn(-y, y))
    }

    Box(
        modifier = modifier
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Keep the tapped point where the finger is.
                            val at = tap - Offset(box.width / 2f, box.height / 2f)
                            offset = clamp(at - at * DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE)
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    val at = centroid - Offset(box.width / 2f, box.height / 2f)
                    offset = clamp(at - (at - offset) * (s / scale) + pan, s)
                    scale = s
                }
            },
    ) {
        Image(
            bitmap = picture,
            contentDescription = "รูปสลิป",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
