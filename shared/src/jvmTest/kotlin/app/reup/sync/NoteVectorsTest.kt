package app.reup.sync

import app.reup.core.NOTES_SHOWN
import app.reup.core.NOTE_COLORS
import app.reup.core.NOTE_STYLES
import app.reup.core.NOTE_STYLE_DEFAULT
import app.reup.core.noteLook
import app.reup.core.noteStyle
import app.reup.core.NOTE_INK
import app.reup.core.NOTE_MAX_CHARS
import app.reup.core.NOTE_PAPER
import app.reup.core.Note
import app.reup.core.noteBody
import app.reup.core.noteColor
import app.reup.core.notesShown
import app.reup.core.noteTilt
import app.reup.core.noteTyped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

// ─── NoteVectorsTest ─────────────────────────────────────────────────────────
//
// Notes are written by both machines, so the statements, the papers and the
// one rule about what a note may say exist twice - src/lib/notes.ts on the
// desktop, Notes.kt and NoteRepo.kt here - and these tests are what keeps the
// two one rule. The vectors come from gen-store-vectors, beside the rest.
//
// The statement worth the file on its own is the peel. It blanks the words on
// the way to being a tombstone, and a phone that only set the flag would send
// a month of somebody's notes into the sync folder with nothing failing.

class NoteVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vectors(): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("store-vectors.json")
            ?: fail("store-vectors.json not found on the test classpath; run `pnpm gen:store-vectors` on the desktop")
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun cases(name: String): JsonArray =
        vectors()[name]?.jsonArray ?: fail("$name is missing from store-vectors.json; run `pnpm gen:store-vectors`")

    @Test
    fun `writes notes with the same five statements as the desktop`() {
        assertEquals(
            cases("noteQueries").map { it.jsonPrimitive.content },
            listOf(LIVE_NOTES_SQL, ADD_NOTE_SQL, EDIT_NOTE_SQL, PEEL_NOTE_SQL, UNPEEL_NOTE_SQL),
        )
    }

    @Test
    fun `has the same papers in the same order, and the same ink`() {
        val want = cases("noteColors").map { it.jsonObject }
        assertEquals(want.map { it["name"]!!.jsonPrimitive.content }, NOTE_COLORS)
        for (c in want) {
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(c["paper"]!!.jsonPrimitive.content, hex(NOTE_PAPER.getValue(name)), "$name paper")
            assertEquals(c["ink"]!!.jsonPrimitive.content, hex(NOTE_INK), "ink")
        }
    }

    @Test
    fun `draws the papers in the same three styles as the desktop`() {
        assertEquals(cases("noteStyles").map { it.jsonPrimitive.content }, NOTE_STYLES)
        assertEquals(cases("noteStyleDefault").single().jsonPrimitive.content, NOTE_STYLE_DEFAULT)
        val looks = cases("noteLooks").map { it.jsonObject }
        assertEquals(NOTE_STYLES.size * NOTE_COLORS.size, looks.size, "every style, every paper")
        for (o in looks) {
            val style = o["style"]!!.jsonPrimitive.content
            val color = o["color"]!!.jsonPrimitive.content
            val look = noteLook(style, color)
            assertEquals(o["paper"]!!.jsonPrimitive.content, hex(look.paper), "$style $color paper")
            assertEquals(o["ink"]!!.jsonPrimitive.content, hex(look.ink), "$style $color ink")
            assertEquals(style == "dark", look.dark)
        }
        // A style from a later version is drawn as the default, never as nothing.
        assertEquals(NOTE_STYLE_DEFAULT, noteStyle("neon"))
        assertEquals(NOTE_STYLE_DEFAULT, noteStyle(null))
    }

    @Test
    fun `keeps the same limits`() {
        val o = cases("noteLimits").single().jsonObject
        assertEquals(o["maxChars"]!!.jsonPrimitive.content.toInt(), NOTE_MAX_CHARS)
        assertEquals(o["shown"]!!.jsonPrimitive.content.toInt(), NOTES_SHOWN)
    }

    @Test
    fun `reads what a note says exactly as the desktop does`() {
        var n = 0
        for (c in cases("noteBody")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val raw = o["raw"]!!.jsonPrimitive.content
            val want = o["expected"]
            if (want == null || want is JsonNull) {
                assertNull(noteBody(raw), id)
            } else {
                assertEquals(want.jsonPrimitive.content, noteBody(raw), id)
            }
            n++
        }
        println("$n noteBody vectors")
    }

    @Test
    fun `leans each note the same way as the desktop`() {
        for (c in cases("noteTilt")) {
            val o = c.jsonObject
            val uid = o["uid"]!!.jsonPrimitive.content
            assertEquals(o["expected"]!!.jsonPrimitive.content.toDouble(), noteTilt(uid).toDouble(), "tilt of '$uid'")
        }
    }

    @Test
    fun `cuts a paste into the box exactly as the desktop does`() {
        var n = 0
        for (c in cases("noteTyped")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val got = noteTyped(o["was"]!!.jsonPrimitive.content, o["now"]!!.jsonPrimitive.content)
            val want = o["expected"]
            if (want == null || want is JsonNull) {
                assertNull(got, id)
            } else {
                val w = want.jsonObject
                assertEquals(w["text"]!!.jsonPrimitive.content, got?.first, "$id text")
                assertEquals(w["cursor"]!!.jsonPrimitive.content.toInt(), got?.second, "$id cursor")
            }
            n++
        }
        println("$n noteTyped vectors")
    }

    @Test
    fun `an unknown paper is drawn as the first`() {
        assertEquals("yellow", noteColor("tartan"))
        assertEquals("yellow", noteColor(null))
        assertEquals("blue", noteColor("blue"))
    }

    @Test
    fun `a board shows the newest few and counts the rest`() {
        val notes = (1..5).map { Note("u$it", "n$it", "yellow", "2026-10-16 0$it:00:00") }
        val (shown, more) = notesShown(notes)
        assertEquals(listOf("u1", "u2", "u3"), shown.map { it.uid })
        assertEquals(2, more)
        assertEquals(listOf("u1") to 4, notesShown(notes, 1).let { (s, m) -> s.map { it.uid } to m })
        assertEquals(0, notesShown(notes.take(2)).second)
        assertEquals(emptyList<Note>() to 0, notesShown(emptyList()))
    }

    @Test
    fun `reads a row into a note, and skips one without the parts a note needs`() {
        val row: DbRow = mapOf(
            "id" to SyncValue.Num(3.0),
            "uid" to SyncValue.Text("u-1"),
            "body" to SyncValue.Text("ซื้อนม"),
            "color" to SyncValue.Text("lilac"),
            "created_at" to SyncValue.Text("2026-10-16 01:00:00"),
        )
        assertEquals(Note("u-1", "ซื้อนม", "yellow", "2026-10-16 01:00:00"), noteFrom(row))
        assertNull(noteFrom(row - "uid"))
        assertNull(noteFrom(row - "body"))
    }

    private fun hex(rgb: Int): String = "#" + rgb.toString(16).uppercase().padStart(6, '0')
}
