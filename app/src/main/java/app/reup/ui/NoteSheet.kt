package app.reup.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reup.NoteUndo
import app.reup.R
import app.reup.Repo
import app.reup.ReupWidget
import app.reup.Ui
import app.reup.core.NOTE_COLORS
import app.reup.core.NOTE_MAX_CHARS
import app.reup.core.noteBody
import app.reup.core.noteLook
import app.reup.core.noteTilt
import app.reup.core.noteTyped
import app.reup.core.pointsIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// ─── NoteSheet.kt — writing a note, over the home screen ─────────────────────
//
// What the widget's + and a tap on a note open: a sheet from the bottom with
// the note itself on it, a square of paper to write on, in the hand the widget
// draws it in. Under it the papers to choose from, and stick it up.
//
// It writes on the paper rather than in a box under a preview, because that is
// what a note is - the words and where they are written are one thing - and
// because one field is one thing to look at for somebody who opened this to
// get a thought out of their head before it goes.

/** The hand the notes are written in, on every screen that shows one. */
val NoteHand = FontFamily(Font(R.font.mali_medium))

/** A note's paper and ink in a style, as the screens draw them; see core/Notes.kt. */
data class NoteColors(val paper: Color, val ink: Color, val dark: Boolean)

fun noteColors(style: String, color: String): NoteColors {
    val look = noteLook(style, color)
    return NoteColors(Color(0xFF000000 or look.paper.toLong()), Color(0xFF000000 or look.ink.toLong()), look.dark)
}

/**
 * The strip of tape a note on dark paper is held up by, in its ink. Dark paper
 * on a dark screen needs something to say where it is; the light ones have
 * their shadow for that.
 */
@Composable
fun NoteTape(ink: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .rotate(-3f)
            .size(width = 40.dp, height = 11.dp)
            .background(ink.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
    )
}

/**
 * Where a note is written and taken off, for the sheet over the home screen.
 * [done] closes it, with what to say if anything.
 */
class NoteSheetModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    uid: String?,
    private val done: (String?) -> Unit,
) {
    /**
     * The note being changed, or null for a new one - which it becomes if the
     * note turns out to have been taken off on the other machine while this
     * was open, so the words can still go up.
     */
    var uid by mutableStateOf(uid)
        private set

    /** How notes look on this phone, as the settings left it. */
    val style: String = Ui.noteStyle(ctx)

    /** The words on the paper. */
    val words = TextFieldState()
    var color by mutableStateOf(NOTE_COLORS[0])
    var loading by mutableStateOf(uid != null)
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun load() {
        val id = uid ?: return
        scope.launch {
            val note = try {
                Repo.notes(ctx).find(id)
            } catch (e: Exception) {
                null
            }
            if (note == null) {
                // Taken off on the other machine since the widget was drawn.
                ReupWidget.redraw(ctx)
                done("โน้ตนี้ถูกแกะไปแล้ว")
                return@launch
            }
            words.setTextAndPlaceCursorAtEnd(note.body)
            color = note.color
            loading = false
        }
    }

    /**
     * Typing: never more than a note may say, counted as noteBody counts, so
     * the counter and the paper agree - and a paste that would go over keeps
     * what was written and as much of itself as fits. The line under the
     * paper about what went wrong goes with the next key.
     */
    val typing: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            val now = asCharSequence().toString()
            val was = originalText.toString()
            if (now == was) return
            problem = null
            val (text, cursor) = noteTyped(was, now) ?: return
            replace(0, length, text)
            selection = TextRange(cursor)
        }
    }

    /** How much is written, out of how much may be. */
    val count: String
        get() = "${pointsIn(words.text.toString())}/$NOTE_MAX_CHARS"

    fun save() {
        if (busy || loading) return
        val text = words.text.toString()
        val id = uid
        if (noteBody(text) == null) {
            // Every word rubbed out of a note is the note taken off, with the
            // undo, as on the desktop. A new one with nothing on it is nothing.
            if (id != null) peel() else problem = "ยังไม่ได้เขียนอะไร"
            return
        }
        busy = true
        scope.launch {
            try {
                val notes = Repo.notes(ctx)
                if (id == null) {
                    notes.add(text, color)
                } else if (!notes.edit(id, text, color)) {
                    // Taken off on the other machine while this was open. The
                    // words stay where they are, and the next tap sticks them
                    // up as a new note.
                    uid = null
                    problem = "โน้ตนี้ถูกแกะจากอีกเครื่องไปแล้ว กดแปะเพื่อแปะเป็นแผ่นใหม่"
                    busy = false
                    return@launch
                }
                afterWrite(ctx)
                done(if (id == null) "แปะแล้ว" else "แก้แล้ว")
            } catch (e: Exception) {
                problem = "บันทึกไม่ได้ " + (e.message ?: e.toString())
                busy = false
            }
        }
    }

    fun peel() {
        val id = uid ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                Repo.notes(ctx).peel(id)?.let { NoteUndo.keep(ctx, it) }
                afterWrite(ctx)
                done("แกะแล้ว")
            } catch (e: Exception) {
                problem = "แกะไม่ได้ " + (e.message ?: e.toString())
                busy = false
            }
        }
    }

    companion object {
        /**
         * Lives as long as the process, so the sync started as a sheet closes is
         * not cancelled with the sheet. Six seconds and no more, as everywhere
         * a sync is started without being asked for.
         */
        private val background = CoroutineScope(Dispatchers.Default + SupervisorJob())

        /** The widget redrawn now; the other machine told soon after. */
        fun afterWrite(ctx: Context) {
            ReupWidget.redraw(ctx)
            background.launch {
                try {
                    withTimeout(6_000) { Repo.syncQuietly(ctx) }
                } catch (e: Exception) {
                    // The next sync carries it.
                }
            }
        }
    }
}

/** The sheet itself: a dim over the home screen, and the paper on a card from the bottom. */
@Composable
fun NoteSheet(model: NoteSheetModel, onDismiss: () -> Unit) {
    val c = Reup.colors
    val focus = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = Size.reading)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.card)
                // Taps on the card stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(start = Size.gutter, end = Size.gutter, top = 20.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (model.uid == null) "แปะโน้ต" else "แก้โน้ต",
                style = ReupType.barTitle,
                color = c.text,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            NotePaperField(
                state = model.words,
                typing = model.typing,
                color = model.color,
                style = model.style,
                tilt = model.uid?.let(::noteTilt) ?: -1.5f,
                enabled = !model.loading && !model.busy,
                focus = focus,
            )
            Spacer(Modifier.height(18.dp))
            PaperChoices(selected = model.color, style = model.style, onPick = { model.color = it })
            Text(
                text = model.problem ?: model.count,
                style = ReupType.support,
                color = if (model.problem != null) c.warn else c.faint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model.uid != null) {
                    TextAction(text = "แกะออก", color = c.warn, onClick = model::peel, enabled = !model.busy && !model.loading)
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    SecondaryButton(text = "ยกเลิก", onClick = onDismiss, enabled = !model.busy)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    PrimaryButton(text = if (model.uid == null) "แปะ" else "บันทึก", onClick = model::save, enabled = !model.busy && !model.loading)
                }
            }
        }
    }
    LaunchedEffect(model.loading) {
        if (!model.loading) {
            try {
                focus.requestFocus()
            } catch (e: IllegalStateException) {
                // Not laid out yet; the keyboard opens with the window instead.
            }
        }
    }
}

/** A square of paper you write on, leaning a little, with a shadow under it. */
@Composable
private fun NotePaperField(
    state: TextFieldState,
    typing: InputTransformation,
    color: String,
    style: String,
    tilt: Float,
    enabled: Boolean,
    focus: FocusRequester,
) {
    val look = noteColors(style, color)
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth(0.86f)
            .rotate(tilt),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 190.dp)
                .shadow(if (look.dark) 8.dp else 6.dp, shape)
                .background(look.paper, shape)
                .then(if (look.dark) Modifier.border(1.dp, look.ink.copy(alpha = 0.3f), shape) else Modifier)
                .padding(18.dp),
        ) {
            BasicTextField(
                state = state,
                enabled = enabled,
                inputTransformation = typing,
                textStyle = TextStyle(fontFamily = NoteHand, fontSize = 22.sp, lineHeight = 31.sp, color = look.ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4),
                cursorBrush = SolidColor(look.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .focusRequester(focus),
                decorator = { field ->
                    if (state.text.isEmpty()) {
                        Text(
                            text = "จดอะไรไว้กันลืม…",
                            style = TextStyle(
                                fontFamily = NoteHand,
                                fontSize = 22.sp,
                                lineHeight = 31.sp,
                                color = look.ink.copy(alpha = if (look.dark) 0.45f else 0.38f),
                            ),
                        )
                    }
                    field()
                },
            )
        }
        if (look.dark) NoteTape(look.ink, Modifier.align(Alignment.TopCenter).offset(y = (-6).dp))
    }
}

/**
 * The papers, as dots, in the style notes are drawn in. The picked one says so
 * with a ring and a tick, not only with its colour; on dark paper each dot
 * also carries its ink as a ring, which is where its colour is.
 */
@Composable
private fun PaperChoices(selected: String, style: String, onPick: (String) -> Unit) {
    val c = Reup.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (name in NOTE_COLORS) {
            val on = name == selected
            val look = noteColors(style, name)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(look.paper)
                    .border(
                        if (on) 3.dp else if (look.dark) 2.dp else 1.dp,
                        if (on) c.accent else if (look.dark) look.ink.copy(alpha = 0.7f) else c.outline,
                        CircleShape,
                    )
                    .clickable(role = Role.RadioButton) { onPick(name) }
                    .semantics { contentDescription = "กระดาษสี" + paperName(name) },
                contentAlignment = Alignment.Center,
            ) {
                if (on) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = look.ink,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun paperName(name: String): String = when (name) {
    "yellow" -> "เหลือง"
    "pink" -> "ชมพู"
    "blue" -> "ฟ้า"
    "green" -> "เขียว"
    "purple" -> "ม่วง"
    else -> name
}

/**
 * A note as a small square, for the board on the Today tab: the paper, the
 * lean, the hand, and the words cut short when they do not fit.
 */
@Composable
fun NoteSquare(
    body: String,
    color: String,
    style: String,
    tilt: Float,
    width: Dp,
    height: Dp,
    fontSize: TextUnit = 16.sp,
    modifier: Modifier = Modifier,
) {
    val look = noteColors(style, color)
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .rotate(tilt),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(if (look.dark) 6.dp else 4.dp, shape)
                .background(look.paper, shape)
                .then(if (look.dark) Modifier.border(1.dp, look.ink.copy(alpha = 0.3f), shape) else Modifier)
                .padding(10.dp),
        ) {
            Text(
                text = body,
                style = TextStyle(fontFamily = NoteHand, fontSize = fontSize, lineHeight = fontSize * 1.4f, color = look.ink),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (look.dark) NoteTape(look.ink, Modifier.align(Alignment.TopCenter).offset(y = (-5).dp))
    }
}
