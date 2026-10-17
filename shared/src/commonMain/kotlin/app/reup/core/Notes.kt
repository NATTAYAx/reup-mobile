package app.reup.core

// ─── Notes.kt — what is stuck on the home screen ─────────────────────────────
//
// A note is a line or two somebody writes so as not to forget it, the way a
// note goes on a fridge door. It has no cycle, no time and no category, and
// nothing anywhere counts how long it has been up. It stays where it can be
// seen until it is taken off, and taking it off is the end of it.
//
// The desktop writes the same table (src/lib/notes.ts). Everything here that
// both machines have to agree on - the papers, the one rule about what a note
// may say, how far each one leans - is checked against store-vectors.json by
// NoteVectorsTest, because two copies of a rule only stay one rule that way.

/** A note as the screens and the widget draw it. */
data class Note(
    val uid: String,
    /** What was typed, as [noteBody] left it. */
    val body: String,
    /** One of [NOTE_COLORS]; [noteColor] has already turned anything else into the first. */
    val color: String,
    /** "YYYY-MM-DD HH:MM:SS", the column's own default. Only ever compared. */
    val createdAt: String,
)

/** The papers, in the order the picker shows them. The first is what an unknown name is drawn as. */
val NOTE_COLORS: List<String> = listOf("yellow", "pink", "blue", "green", "purple")

/**
 * Each paper as 0xRRGGBB, and the one ink on all of them - dark enough for
 * every paper to clear seven to one, because a note is read in a glance.
 */
val NOTE_PAPER: Map<String, Int> = mapOf(
    "yellow" to 0xFFF0A0,
    "pink" to 0xFFCADC,
    "blue" to 0xC9E5FF,
    "green" to 0xD4F2C4,
    "purple" to 0xE3D6FF,
)
const val NOTE_INK: Int = 0x2A2633

// ─── how the papers look ─────────────────────────────────────────────────────
//
// One setting for every note, picked by the person and synced like the theme
// (KEY_NOTE_STYLE): the five papers as they come, a little dimmer, or dark.
// The desktop holds the same table (notes.ts), and the store vectors hold the
// two together. Each note keeps its own colour; this is how that colour is
// drawn. Every pairing clears 8 to 1.

/** In picker order. The names are what is stored. */
val NOTE_STYLES: List<String> = listOf("bright", "muted", "dark")

/** What a note looks like before anybody has picked. */
const val NOTE_STYLE_DEFAULT: String = "muted"

/** One note's paper and ink as 0xRRGGBB, and whether it is the dark kind, which also draws an edge and a strip of tape. */
data class NoteLook(val paper: Int, val ink: Int, val dark: Boolean)

/** Each style's paper and ink for each colour, as 0xRRGGBB. */
val NOTE_LOOKS: Map<String, Map<String, Pair<Int, Int>>> = mapOf(
    "bright" to NOTE_COLORS.associateWith { NOTE_PAPER.getValue(it) to NOTE_INK },
    "muted" to mapOf(
        "yellow" to (0xE3D58C to NOTE_INK),
        "pink" to (0xE0B6C4 to NOTE_INK),
        "blue" to (0xB4CAE0 to NOTE_INK),
        "green" to (0xBCD5B0 to NOTE_INK),
        "purple" to (0xC8BDE2 to NOTE_INK),
    ),
    "dark" to mapOf(
        "yellow" to (0x2A2817 to 0xFDE68A),
        "pink" to (0x2E1A24 to 0xFBCFE8),
        "blue" to (0x172436 to 0xBFDBFE),
        "green" to (0x18291F to 0xBBF7D0),
        "purple" to (0x231D38 to 0xDDD6FE),
    ),
)

/** A style this version knows, the default for anything else. */
fun noteStyle(raw: String?): String = if (raw != null && raw in NOTE_STYLES) raw else NOTE_STYLE_DEFAULT

/** How a note of [color] is drawn in [style]; unknown names of either are read as the defaults. */
fun noteLook(style: String, color: String): NoteLook {
    val s = noteStyle(style)
    val (paper, ink) = NOTE_LOOKS.getValue(s).getValue(noteColor(color))
    return NoteLook(paper, ink, s == "dark")
}

/** About what fits on a square of paper in a hand you can still read. */
const val NOTE_MAX_CHARS: Int = 120

/** How many the widget and the boards show at once; the rest are counted. */
const val NOTES_SHOWN: Int = 3

/** How far each note leans, in degrees; which one is picked by the note's uid. */
val NOTE_TILTS: List<Float> = listOf(-3f, 2f, -1.5f, 3f, -2.5f, 1.5f)

/** A paper this version knows, the first for anything else. */
fun noteColor(raw: String?): String = if (raw != null && raw in NOTE_COLORS) raw else NOTE_COLORS[0]

/**
 * What a note says, or null for nothing worth sticking up.
 *
 * Each line trimmed, blank lines dropped - a square of paper has no room for
 * paragraphs - and the whole cut at [NOTE_MAX_CHARS] code points, so a cut
 * never lands inside an emoji. Nothing else: it is somebody's own words.
 *
 * "Trimmed" means what JavaScript's trim means, spelled out below, because the
 * desktop does it with that and Kotlin's own trim draws the line in a slightly
 * different place: it also strips four control characters JavaScript keeps,
 * and keeps the byte order mark JavaScript strips.
 */
fun noteBody(raw: String): String? {
    val text = raw.replace(Regex("\r\n?"), "\n")
        .split('\n')
        .map { it.trim(::isJsSpace) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    if (text.isEmpty()) return null
    val i = pointsEnd(text, NOTE_MAX_CHARS)
    return if (i >= text.length) text else text.substring(0, i).trimEnd(::isJsSpace)
}

/**
 * What a note's box holds once typing has turned [was] into [now], if [now]
 * is longer than a note may be: what was there already stays, and of what
 * came in - a paste, most likely - as much as fits, cut where [noteBody]
 * counts. With where the cursor goes, after what came in. Null when [now]
 * fits as it is.
 */
fun noteTyped(was: String, now: String): Pair<String, Int>? {
    if (pointsIn(now) <= NOTE_MAX_CHARS) return null
    // What came in is what lies between the part the two start with and the
    // part they end with; neither edge may fall inside an emoji.
    val shorter = minOf(was.length, now.length)
    var start = 0
    while (start < shorter && was[start] == now[start]) start++
    if (start > 0 && now[start - 1].isHighSurrogate()) start--
    var end = 0
    while (end < shorter - start && was[was.length - 1 - end] == now[now.length - 1 - end]) end++
    if (end > 0 && now[now.length - end].isLowSurrogate()) end--
    val head = now.substring(0, start)
    val tail = now.substring(now.length - end)
    val came = now.substring(start, now.length - end)
    val room = NOTE_MAX_CHARS - pointsIn(head) - pointsIn(tail)
    val kept = if (room <= 0) "" else came.substring(0, pointsEnd(came, room))
    val text = head + kept + tail
    // Only if what was there was already too long, which typing cannot make.
    if (pointsIn(text) > NOTE_MAX_CHARS) return text.substring(0, pointsEnd(text, NOTE_MAX_CHARS)).let { it to it.length }
    return text to (head.length + kept.length)
}

/** How many code points [text] holds - an emoji is one, as it is in JavaScript's [...text]. */
fun pointsIn(text: String): Int {
    var n = 0
    var i = 0
    while (i < text.length) {
        i = pointsEnd(text, 1, i)
        n++
    }
    return n
}

/**
 * Where [n] code points from [from] end in [text], or its end. Walked by hand
 * rather than with codePointCount, which is the JVM's and not in the common
 * library this file has to compile against for iOS.
 */
private fun pointsEnd(text: String, n: Int, from: Int = 0): Int {
    var i = from
    var points = 0
    while (i < text.length && points < n) {
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        points++
    }
    return i
}

/**
 * A few degrees either way, the same for one note every time and on both
 * machines. The hash is the JVM's String.hashCode written out, because the
 * desktop writes it out too and Kotlin promises that hash only on the JVM; the
 * index is a floor modulo, since abs() of the smallest Int is still negative.
 */
fun noteTilt(uid: String): Float {
    var h = 0
    for (c in uid) h = 31 * h + c.code
    return NOTE_TILTS[h.mod(NOTE_TILTS.size)]
}

/**
 * The notes a board of [room] shows, newest first, and how many are left over.
 * The list arrives newest first from the query; this only cuts it.
 */
fun notesShown(notes: List<Note>, room: Int = NOTES_SHOWN): Pair<List<Note>, Int> {
    val n = room.coerceIn(0, NOTES_SHOWN)
    return notes.take(n) to (notes.size - minOf(n, notes.size))
}

/** JavaScript's WhiteSpace and LineTerminator, which is what its trim removes. */
private fun isJsSpace(c: Char): Boolean =
    c == '\t' || c == '\u000B' || c == '\u000C' || c == ' ' || c == '\u00A0' || c == '\uFEFF' ||
        c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' ||
        c.category == CharCategory.SPACE_SEPARATOR
