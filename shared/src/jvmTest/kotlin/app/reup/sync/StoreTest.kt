package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// ─── StoreTest ───────────────────────────────────────────────────────────────
//
// The desktop store is proven against two real SQLite databases built from the
// real schema, with the real triggers, in check-sync.ts. That proof cannot be
// repeated here — there is no SQLite on the JVM test classpath and adding one
// would mean a new dependency for a check the desktop already runs.
//
// So this proves something narrower and sufficient: that this port emits the
// same statements and makes the same decisions for the same inputs. If the
// strings match, the desktop's proof that those strings do the right thing
// against real SQLite carries over.
//
// The shapes in the vector file are not invented. They were read out of a real
// database with PRAGMA, so what is being tested against is the tables that
// exist rather than a tidy example of them.
//
// WHY WHITESPACE IS COLLAPSED BEFORE COMPARING
//
// A trigger body is a template literal on one side and a raw string on the
// other, and the two will never indent the same. Insisting on identical
// whitespace would fail for a reason nobody cares about, and a test that fails
// for reasons nobody cares about is a test people learn to ignore.

private fun drive(block: suspend () -> Unit) {
    var thrown: Throwable? = null
    var finished = false
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                thrown = result.exceptionOrNull()
                finished = true
            }
        },
    )
    check(finished) { "the block actually suspended; the fakes here never do" }
    thrown?.let { throw it }
}

/**
 * A database that answers PRAGMA from a shape and everything else from a script,
 * writing down every statement it is given.
 *
 * Not a SQLite. It exists so the store's sequence of statements can be inspected
 * — which query it runs before which write, and with what bound — because that
 * order is the part the desktop's proof depends on. The PRAGMA answers are built
 * from the shapes in the vector file, which were themselves read out of a real
 * database, so readShape is exercised against the tables that exist rather than
 * against a convenient invention.
 */
private class RecordingDb(private val shapes: Map<String, TableShape> = emptyMap()) : Db {
    private val answers = mutableMapOf<String, List<DbRow>>()
    val executed = mutableListOf<Statement>()
    val queried = mutableListOf<Statement>()

    fun answer(match: String, rows: List<DbRow>) {
        answers[match] = rows
    }

    override suspend fun execute(sql: String, params: List<SyncValue>) {
        executed += Statement(sql, params)
    }

    override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> {
        queried += Statement(sql, params)
        pragma(sql)?.let { return it }
        for ((match, rows) in answers) if (sql.contains(match)) return rows
        return emptyList()
    }

    private fun arg(sql: String): String = sql.substringAfter("(").substringBefore(")")

    private fun pragma(sql: String): List<DbRow>? = when {
        sql.startsWith("PRAGMA table_info(") -> {
            val s = shapes[arg(sql)]
            s?.columns?.map {
                mapOf("name" to SyncValue.Text(it), "type" to SyncValue.Text(s.types[it] ?: ""))
            } ?: emptyList()
        }

        sql.startsWith("PRAGMA index_list(") -> {
            val name = arg(sql)
            val s = shapes[name]
            if (s == null) {
                emptyList()
            } else {
                // The uid index is ours and is created by the migrations; the
                // rest come from UNIQUE clauses and are the ones that matter.
                val out = mutableListOf<DbRow>(
                    mapOf(
                        "name" to SyncValue.Text("idx_${name}_uid"),
                        "unique" to SyncValue.Num(1.0),
                        "origin" to SyncValue.Text("c"),
                    ),
                )
                s.naturalKeys.forEachIndexed { i, _ ->
                    out += mapOf(
                        "name" to SyncValue.Text("sqlite_autoindex_${name}_${i + 1}"),
                        "unique" to SyncValue.Num(1.0),
                        "origin" to SyncValue.Text("u"),
                    )
                }
                out
            }
        }

        sql.startsWith("PRAGMA index_info(") -> {
            val idx = arg(sql)
            if (idx.endsWith("_uid")) {
                listOf(mapOf("name" to SyncValue.Text("uid")))
            } else {
                val body = idx.removePrefix("sqlite_autoindex_")
                val n = body.substringAfterLast("_").toIntOrNull() ?: 1
                val table = body.substringBeforeLast("_")
                shapes[table]?.naturalKeys?.getOrNull(n - 1)
                    ?.map { mapOf("name" to SyncValue.Text(it)) }
                    ?: emptyList()
            }
        }

        else -> null
    }
}

class StoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun flat(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    private fun vectors(): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("store-vectors.json")
            ?: fail(
                "store-vectors.json not found on the test classpath. It is generated on the " +
                        "desktop side with `pnpm gen:store-vectors` and copied to " +
                        "shared/src/jvmTest/resources/.",
            )
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun cases(name: String): JsonArray =
        vectors()[name]?.jsonArray ?: fail("$name is missing from store-vectors.json")

    private fun str(o: JsonObject, key: String): String = o[key]!!.jsonPrimitive.content

    private fun shapeFrom(o: JsonObject): TableShape = TableShape(
        name = str(o, "name"),
        columns = o["columns"]!!.jsonArray.map { it.jsonPrimitive.content },
        types = o["types"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        hasDeleted = o["hasDeleted"]!!.jsonPrimitive.content == "true",
        naturalKeys = o["naturalKeys"]!!.jsonArray.map { k ->
            k.jsonArray.map { it.jsonPrimitive.content }
        },
    )

    private fun shapes(): Map<String, TableShape> =
        cases("shapes").associate { val s = shapeFrom(it.jsonObject); s.name to s }

    private fun rowFrom(o: JsonObject): DbRow = o.mapValues { BatchCodec.toValue(it.value) }

    private fun paramsFrom(a: JsonArray): List<SyncValue> = a.map { BatchCodec.toValue(it) }

    private fun recordFrom(o: JsonObject): ChangeRecord = BatchCodec.decodeRecord(o)

    // ── the migrations ──────────────────────────────────────────────────────

    @Test
    fun `emits the same migrations as the desktop, in the same order`() {
        val want = cases("migrations")
        val got = SyncMigrations.statements()
        assertEquals(want.size, got.size, "the number of migration statements differs")
        for (i in got.indices) {
            val w = want[i].jsonObject
            assertEquals(str(w, "sql"), flat(got[i].sql), "migration $i")
            assertEquals(
                str(w, "ignoreErrors") == "true",
                got[i].ignoreErrors,
                "migration $i: whether failing is normal",
            )
        }
        println("${got.size} migration statements")
    }

    @Test
    fun `spells the clock exactly as the desktop does`() {
        // Every timestamp either device writes comes out of these three, and
        // they are the one thing in this project both languages retype by hand.
        // Two copies that drift would put the two clocks on different scales
        // with nothing erroring anywhere.
        val want = cases("clock").map { it.jsonPrimitive.content }
        assertEquals(listOf(SQL_BUMP, SQL_CLOCK_READ, SQL_SEEN), want)
    }

    @Test
    fun `refills the queue with the same statements as the desktop`() {
        // What a snapshot is made of. Two implementations that agree on the
        // seven tables but not on the flag statement around them would produce
        // two devices that disagree about what a snapshot contains, which is the
        // one thing the retention rule rests on not happening.
        val want = cases("reseed")
        val got = SyncMigrations.outboxReseed()
        assertEquals(want.size, got.size, "the number of reseed statements differs")
        for (i in got.indices) {
            assertEquals(str(want[i].jsonObject, "sql"), flat(got[i].sql), "reseed $i")
        }
        assertTrue(got.isNotEmpty(), "no reseed statements ran")
        println("${got.size} reseed statements")
    }

    @Test
    fun `reads a stored quiet window exactly as the desktop does`() {
        // The value on the row is the desktop's localStorage string verbatim, so
        // this function is the only thing standing between one format and two
        // readings of it. The cases are the ones where a reasonable second
        // implementation would differ: a missing flag, a flag that is the string
        // "true", bounds that are equal, bounds that are unreadable while the
        // switch is on.
        var n = 0
        for (c in cases("quiet")) {
            val o = c.jsonObject
            val raw = (o["raw"] as? JsonPrimitive)?.contentOrNull
            val want = o["expected"]!!.jsonObject
            val got = parseQuiet(raw)
            when (str(want, "kind")) {
                "unknown" -> assertEquals(QuietSetting.Unknown, got, "raw=$raw")
                "off" -> assertEquals(QuietSetting.Off, got, "raw=$raw")
                "window" -> assertEquals(
                    QuietSetting.Window(str(want, "start"), str(want, "end")),
                    got,
                    "raw=$raw",
                )
                else -> fail("unknown kind in vector: $want")
            }
            n++
        }
        println("$n quiet vectors")
        assertTrue(n > 0, "no quiet vectors ran")
    }

    @Test
    fun `tidies dates on the way in exactly as the desktop does`() {
        var n = 0
        for (c in cases("sanitizeText")) {
            val o = c.jsonObject
            val raw = (o["raw"] as? JsonPrimitive)?.contentOrNull
            val want = (o["expected"] as? JsonPrimitive)?.contentOrNull
            assertEquals(want, sanitizeText(raw), "raw=$raw")
            n++
        }
        println("$n sanitizeText vectors")
        assertTrue(n > 0, "no sanitizeText vectors ran")
    }

    @Test
    fun `turns a draft into the same row the desktop would write`() {
        // The phone is the second thing that can make a task, and sixteen
        // coercions reproduced from reading the other side is the shape this
        // project keeps finding on the wrong end of a bug.
        val columns = cases("taskColumns").map { it.jsonPrimitive.content }
        assertEquals(TASK_COLUMNS, columns, "the column order differs")

        var n = 0
        for (c in cases("taskDraft")) {
            val o = c.jsonObject
            val d = o["draft"]!!.jsonObject
            fun s(k: String): String? = (d[k] as? JsonPrimitive)?.contentOrNull
            fun b(k: String): Boolean {
                val p = d[k] as? JsonPrimitive ?: return false
                return p.contentOrNull == "true" || p.contentOrNull == "1"
            }
            val draft = TaskDraft(
                name = s("name"),
                description = s("description"),
                category = s("category"),
                resetType = s("reset_type"),
                resetTime = s("reset_time"),
                resetDay = s("reset_day"),
                resetIntervalDays = s("reset_interval_days"),
                anchorDate = s("anchor_date"),
                eventStart = s("event_start"),
                eventEnd = s("event_end"),
                specificDate = s("specific_date"),
                isPriority = b("is_priority"),
                isUrgent = b("is_urgent"),
                minStep = s("min_step"),
                timeZone = s("time_zone"),
                intent = s("intent"),
            )
            val label = s("name") + "/" + s("reset_type")

            val wantValues = o["values"]!!.jsonArray.map { e ->
                (e as? JsonPrimitive)?.contentOrNull
            }
            val gotValues = taskValues(draft).map { v ->
                when (v) {
                    is SyncValue.Null -> null
                    is SyncValue.Text -> v.value
                    // The desktop writes whole numbers without a fraction, and
                    // Kotlin prints 1.0. Compared as the numbers they are, not
                    // as the strings two languages happen to print them as —
                    // which is the disagreement Merge already had to settle.
                    is SyncValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString()
                    else v.value.toString()
                    is SyncValue.Bool -> if (v.value) "1" else "0"
                }
            }
            assertEquals(wantValues, gotValues, "values for $label")

            val wantProblems = o["problems"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(wantProblems, taskProblems(draft), "problems for $label")
            n++
        }
        println("$n taskDraft vectors")
        assertTrue(n > 0, "no taskDraft vectors ran")
    }

    @Test
    fun `builds the same UPDATE the desktop would`() {
        assertEquals(
            cases("taskEditable").map { it.jsonPrimitive.content },
            TASK_EDITABLE.keys.toList(),
            "the editable columns differ, or their order does",
        )

        var n = 0
        for (c in cases("taskUpdate")) {
            val o = c.jsonObject
            val given = o["fields"]!!.jsonObject
            // A map that keeps the difference between "absent" and "null",
            // because that difference is the whole question this file answers.
            val fields = LinkedHashMap<String, String?>()
            for ((k, v) in given) fields[k] = (v as? JsonPrimitive)?.contentOrNull

            val got = taskUpdate(fields)
            assertEquals(
                o["columns"]!!.jsonArray.map { it.jsonPrimitive.content },
                got.columns,
                "columns for $given",
            )
            val want = o["values"]!!.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
            val mine = got.values.map { v ->
                when (v) {
                    is SyncValue.Null -> null
                    is SyncValue.Text -> v.value
                    is SyncValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString()
                    else v.value.toString()
                    is SyncValue.Bool -> if (v.value) "1" else "0"
                }
            }
            assertEquals(want, mine, "values for $given")
            n++
        }
        println("$n taskUpdate vectors")
        assertTrue(n > 0, "no taskUpdate vectors ran")
    }

    @Test
    fun `turns an expense draft into the same row the desktop would write`() {
        assertEquals(
            cases("expenseColumns").map { it.jsonPrimitive.content },
            EXPENSE_COLUMNS,
            "the expense column order differs",
        )
        val known = cases("expenseCategories").map { it.jsonPrimitive.content }

        var n = 0
        for (c in cases("expenseDraft")) {
            val o = c.jsonObject
            val g = o["draft"]!!.jsonObject
            fun s(k: String): String? = (g[k] as? JsonPrimitive)?.contentOrNull
            val draft = ExpenseDraft(
                amount = s("amount"),
                currency = s("currency"),
                category = s("category"),
                note = s("note"),
                date = s("date"),
                slipRef = s("slip_ref"),
            )
            val label = "${s("amount")}/${s("category")}/${s("date")}"

            val want = o["values"]!!.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
            val mine = expenseValues(draft, known).map { v ->
                when (v) {
                    is SyncValue.Null -> null
                    is SyncValue.Text -> v.value
                    // The desktop prints a whole number without its fraction.
                    // Compared as the numbers they are, not as the strings two
                    // languages happen to print them as.
                    is SyncValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString()
                    else v.value.toString()
                    is SyncValue.Bool -> if (v.value) "1" else "0"
                }
            }
            assertEquals(want, mine, "values for $label")
            assertEquals(
                o["problems"]!!.jsonArray.map { it.jsonPrimitive.content },
                expenseProblems(draft, known),
                "problems for $label",
            )
            n++
        }
        println("$n expenseDraft vectors")
        assertTrue(n > 0, "no expenseDraft vectors ran")
    }

    @Test
    fun `asks the month with the same statements as the desktop`() {
        // A query that names a column the table does not have is not a wrong
        // answer, it is a statement that will not run. The desktop's copies are
        // exercised against a real database in check-sync; these have to be the
        // same strings for that to mean anything here.
        //
        // The fourth is the list, and it pins a number as well as a shape: the
        // twenty lives inside the string, so "lately" cannot come to mean one
        // thing on one device and another on the other.
        assertEquals(
            cases("moneyQueries").map { it.jsonPrimitive.content },
            listOf(
                SQL_MONTH_SPENT,
                SQL_MONTH_RECEIVED,
                SQL_MONTH_OTHER_COUNT,
                SQL_RECENT_MONEY,
                SQL_DELETE_EXPENSE,
                SQL_DELETE_INCOME,
            ),
        )
        // Neither of these can fail loudly if the two sides disagree. One
        // device just files a month in a unit the other one filters out.
        assertEquals(
            cases("moneyFallbacks").map { it.jsonPrimitive.content },
            listOf(CURRENCY_FALLBACK, CATEGORY_FALLBACK),
        )
    }

    @Test
    fun `turns the recent rows into entries, and drops the ones it cannot read`() {
        // The statement itself is held by the vector above and run against a
        // real database on the desktop. What is left unheld is this mapper, and
        // it is the half that decides which column ends up in which column on
        // screen — a payment filed as spending is not a crash, it is a number
        // in the wrong place with nothing to notice it by.
        val db = RecordingDb()
        db.answer(
            "ORDER BY date DESC",
            listOf(
                mapOf(
                    "kind" to SyncValue.Text("out"),
                    "uid" to SyncValue.Text("u-1"),
                    "date" to SyncValue.Text("2026-08-20"),
                    "amount" to SyncValue.Num(60.0),
                    "currency" to SyncValue.Text("THB"),
                    "tag" to SyncValue.Text("food"),
                    "note" to SyncValue.Text("\u0e01\u0e32\u0e41\u0e1f"),
                ),
                mapOf(
                    "kind" to SyncValue.Text("in"),
                    "uid" to SyncValue.Text("u-2"),
                    "date" to SyncValue.Text("2026-08-19"),
                    "amount" to SyncValue.Num(6516.0),
                    "currency" to SyncValue.Text("USD"),
                    "tag" to SyncValue.Text("TELUS"),
                    "note" to SyncValue.Null,
                ),
                // Neither of these can come out of the statement above. They
                // are here because "cannot happen" is how a mapper ends up
                // inventing a direction the day it does.
                mapOf(
                    "kind" to SyncValue.Text("sideways"),
                    "uid" to SyncValue.Text("u-3"),
                    "date" to SyncValue.Text("2026-08-18"),
                    "amount" to SyncValue.Num(1.0),
                    "currency" to SyncValue.Text("THB"),
                ),
                mapOf(
                    "kind" to SyncValue.Text("out"),
                    "date" to SyncValue.Text("2026-08-17"),
                    "amount" to SyncValue.Num(2.0),
                    "currency" to SyncValue.Text("THB"),
                ),
            ),
        )

        var got: List<MoneyEntry> = emptyList()
        drive { got = MoneyRepo(db).recent() }

        assertEquals(2, got.size, "a row with no direction or no uid is dropped")
        assertEquals(false, got[0].incoming)
        assertEquals("food", got[0].tag)
        assertEquals(true, got[1].incoming, "a payment is not filed as spending")
        assertEquals("TELUS", got[1].tag, "the name on a payment sits where a category would")
        assertEquals("USD", got[1].currency, "the unit comes from the row, not the screen")
        assertEquals(null, got[1].note)

        // Ordering is the statement's job, not this one's: it is asked for
        // exactly as pinned, with nothing bound.
        val asked = db.queried.last()
        assertEquals(SQL_RECENT_MONEY, asked.sql)
        assertEquals(emptyList(), asked.params)
    }

    @Test
    fun `takes a row back from the table it came from`() {
        // The direction picks the statement, and picking the wrong one is not a
        // crash: it is an UPDATE that matches nothing, a row that stays, and a
        // screen that has already removed it from the list.
        val db = RecordingDb()
        val out = MoneyEntry(false, "u-out", "2026-08-20", 60.0, "THB", "food", null)
        val income = MoneyEntry(true, "u-in", "2026-08-19", 6516.0, "USD", "TELUS", null)

        drive { MoneyRepo(db).delete(out) }
        drive { MoneyRepo(db).delete(income) }

        assertEquals(SQL_DELETE_EXPENSE, db.executed[0].sql)
        assertEquals(listOf(SyncValue.Text("u-out")), db.executed[0].params)
        assertEquals(SQL_DELETE_INCOME, db.executed[1].sql)
        assertEquals(listOf(SyncValue.Text("u-in")), db.executed[1].params)
    }

    @Test
    fun `builds the same edit as the desktop, column for column`() {
        // The desktop runs these against a real database in check-sync. The
        // statement is built rather than written, so the thing that has to match
        // is the builder: two devices assembling the same edit in a different
        // order assemble two different strings, and neither one is wrong on its
        // own screen.
        var n = 0
        for (c in cases("moneyUpdate")) {
            val o = c.jsonObject
            val table = o["table"]!!.jsonPrimitive.content
            val fields = mutableMapOf<String, Any?>()
            for ((k, v) in o["fields"]!!.jsonObject) {
                val p = v.jsonPrimitive
                fields[k] = if (p.isString) p.content else p.content
            }
            val (columns, values) = moneyUpdate(table, fields)
            assertEquals(
                o["columns"]!!.jsonArray.map { it.jsonPrimitive.content },
                columns,
                "columns for $fields",
            )
            assertEquals(o["sql"]!!.jsonPrimitive.content, moneyUpdateSql(table, columns), "sql for $fields")
            assertEquals(o["values"]!!.jsonArray.size, values.size, "value count for $fields")
            n++
        }
        println("$n moneyUpdate vectors")
        assertTrue(n > 0, "no moneyUpdate vectors ran")
    }

    @Test
    fun `an edit is refused by the same rules a new row is`() {
        // An edit that can put a row into a state a new row could not reach is a
        // second definition of what a valid row is, and only one of the two would
        // ever be looked at again.
        val db = RecordingDb()
        val known = listOf("food", "other")
        var refused: List<String> = emptyList()
        drive {
            refused = MoneyRepo(db).editExpense(
                "u-1",
                ExpenseDraft(amount = "0", currency = "THB", category = "food", note = "", date = "2026-08-19"),
                known,
            )
        }
        assertEquals(listOf("amount-not-positive"), refused)
        assertEquals(0, db.executed.size, "a refused edit writes nothing")

        drive {
            MoneyRepo(db).editExpense(
                "u-1",
                ExpenseDraft(amount = "45", currency = "THB", category = "gone", note = "x", date = "2026-08-19"),
                known,
            )
        }
        val wrote = db.executed[0]
        assertEquals(SyncValue.Text("u-1"), wrote.params.last(), "the uid is bound last")
        assertEquals(6, wrote.params.size)
        assertEquals(
            SyncValue.Text("other"),
            wrote.params[2],
            "an unknown category is filed rather than losing the edit",
        )
    }

    @Test
    fun `turns an income draft into the same row the desktop would write`() {
        assertEquals(
            cases("incomeColumns").map { it.jsonPrimitive.content },
            INCOME_COLUMNS,
            "the income column order differs",
        )

        var n = 0
        for (c in cases("incomeDraft")) {
            val o = c.jsonObject
            val g = o["draft"]!!.jsonObject
            fun s(k: String): String? = (g[k] as? JsonPrimitive)?.contentOrNull
            val draft = IncomeDraft(
                amount = s("amount"),
                source = s("source"),
                note = s("note"),
                date = s("date"),
                currency = s("currency"),
            )
            val label = "${s("amount")}/${s("source")}/${s("date")}"

            val want = o["values"]!!.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
            val mine = incomeValues(draft).map { v ->
                when (v) {
                    is SyncValue.Null -> null
                    is SyncValue.Text -> v.value
                    is SyncValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString()
                    else v.value.toString()
                    is SyncValue.Bool -> if (v.value) "1" else "0"
                }
            }
            assertEquals(want, mine, "values for $label")
            assertEquals(
                o["problems"]!!.jsonArray.map { it.jsonPrimitive.content },
                incomeProblems(draft),
                "problems for $label",
            )
            n++
        }
        println("$n incomeDraft vectors")
        assertTrue(n > 0, "no incomeDraft vectors ran")
    }

    @Test
    fun `reads a stored row back as the strings a form would show`() {
        val row = mapOf(
            "uid" to SyncValue.Text("u1"),
            "name" to SyncValue.Text("\u0e22\u0e32\u0e04\u0e27\u0e32\u0e21\u0e14\u0e31\u0e19"),
            "reset_type" to SyncValue.Text("weekly"),
            // Sunday, and SQLite hands numbers over as doubles. A day box
            // reading "0.0" looks like a bug to the person holding the phone.
            "reset_day" to SyncValue.Num(0.0),
            "reset_time" to SyncValue.Text("09:00"),
            "is_priority" to SyncValue.Num(1.0),
            "specific_date" to SyncValue.Null,
            // Not editable. Must not come back, or a form would offer to change
            // something it has no way to save.
            "completed_until" to SyncValue.Text("2026-08-25T00:00:00Z"),
        )
        val got = taskEditFields(row)

        assertEquals("0", got["reset_day"])
        assertEquals("1", got["is_priority"])
        assertEquals("09:00", got["reset_time"])
        assertEquals(null, got["specific_date"])
        assertTrue("specific_date" in got, "a stored null must stay present, not vanish")
        assertTrue("completed_until" !in got, "a column no form can write leaked into one")
        // Absent means "do not touch", null means "clear it". A row from an
        // older desktop must not have its missing columns wiped by being
        // opened on a phone.
        assertTrue("notes" !in got, "a column the row does not have was invented")
        assertEquals(TASK_EDITABLE.keys.filter { it in got }, got.keys.toList())
    }

    // ── the shapes ──────────────────────────────────────────────────────────

    @Test
    fun `works out the same payload columns as the desktop`() {
        var n = 0
        for (c in cases("shapes")) {
            val o = c.jsonObject
            val shape = shapeFrom(o)
            val want = o["payloadColumns"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(want, Rows.payloadColumns(shape), "${shape.name} payload columns")
            // The one that matters most: id must never be in there. It is a
            // different number on every device for the same row.
            assertTrue("id" !in Rows.payloadColumns(shape), "${shape.name} is sending its local id")
            n++
        }
        println("$n shapes")
    }

    // ── rows in ─────────────────────────────────────────────────────────────

    @Test
    fun `turns a row into the same record as the desktop`() {
        var n = 0
        for (c in cases("recordFromRow")) {
            val o = c.jsonObject
            val shape = shapes()[str(o, "table")] ?: fail("no shape for ${str(o, "table")}")
            val got = Rows.recordFromRow(shape, rowFrom(o["row"]!!.jsonObject), str(o, "origin"))
            assertEquals(recordFrom(o["expected"]!!.jsonObject), got, str(o, "id"))
            n++
        }
        println("$n recordFromRow vectors")
    }

    // ── statements out ──────────────────────────────────────────────────────

    @Test
    fun `writes the same upsert as the desktop`() {
        var n = 0
        val shapes = shapes()
        for (c in cases("upsert")) {
            val o = c.jsonObject
            val id = str(o, "id")
            val shape = shapes[str(o, "table")] ?: fail("no shape for ${str(o, "table")}")
            val record = recordFrom(o["record"]!!.jsonObject)
            val got = Rows.upsert(shape, Rows.freeNaturalKeys(shape, record))
            val want = o["expected"]!!.jsonObject
            assertEquals(str(want, "sql"), flat(got.sql), "$id sql")
            assertEquals(paramsFrom(want["params"]!!.jsonArray), got.params, "$id params")
            n++
        }
        println("$n upsert vectors")
    }

    @Test
    fun `reads with the same queries as the desktop`() {
        val shapes = shapes()
        var n = 0
        for (c in cases("pending")) {
            val o = c.jsonObject
            val shape = shapes[str(o, "table")] ?: fail("no shape for ${str(o, "table")}")
            assertEquals(str(o, "expected"), flat(Rows.pending(shape).sql), str(o, "id"))
            n++
        }
        for (c in cases("spill") ) {
            val o = c.jsonObject
            val got = when (str(o, "kind")) {
                "read" -> Rows.spillRead(
                    o["pairs"]!!.jsonArray.map { p ->
                        str(p.jsonObject, "table") to str(p.jsonObject, "uid")
                    },
                )
                "write" -> Rows.spillWrite(str(o, "table"), str(o, "uid"), str(o, "cols"))
                else -> Rows.spillDrop(str(o, "table"), str(o, "uid"))
            }
            val want = o["expected"]!!.jsonObject
            assertEquals(str(want, "sql"), flat(got.sql), "${str(o, "id")} sql")
            assertEquals(paramsFrom(want["params"]!!.jsonArray), got.params, "${str(o, "id")} params")
            n++
        }
        for (c in cases("settle")) {
            val o = c.jsonObject
            val got = Rows.settle(str(o, "table"), str(o, "uid"), str(o, "updatedAt"))
            val want = o["expected"]!!.jsonObject
            assertEquals(str(want, "sql"), flat(got.sql), "${str(o, "id")} sql")
            assertEquals(paramsFrom(want["params"]!!.jsonArray), got.params, "${str(o, "id")} params")
            n++
        }
        for (c in cases("byUids")) {
            val o = c.jsonObject
            val shape = shapes[str(o, "table")] ?: fail("no shape for ${str(o, "table")}")
            val uids = o["uids"]!!.jsonArray.map { it.jsonPrimitive.content }
            val got = Rows.byUids(shape, uids)
            val want = o["expected"]!!.jsonObject
            assertEquals(str(want, "sql"), flat(got.sql), "${str(o, "id")} sql")
            assertEquals(paramsFrom(want["params"]!!.jsonArray), got.params, "${str(o, "id")} params")
            n++
        }
        println("$n query vectors")
    }

    // ── deletion and duplicates ─────────────────────────────────────────────

    @Test
    fun `frees a tombstone's natural key exactly as the desktop does`() {
        var n = 0
        val shapes = shapes()
        for (c in cases("freeNaturalKeys")) {
            val o = c.jsonObject
            val shape = shapes[str(o, "table")] ?: fail("no shape for ${str(o, "table")}")
            val got = Rows.freeNaturalKeys(shape, recordFrom(o["record"]!!.jsonObject))
            assertEquals(recordFrom(o["expected"]!!.jsonObject), got, str(o, "id"))
            n++
        }
        println("$n freeNaturalKeys vectors")
    }

    @Test
    fun `settles a duplicate the same way the desktop does`() {
        var n = 0
        val shapes = shapes()
        for (c in cases("clash")) {
            val o = c.jsonObject
            val id = str(o, "id")
            val shape = shapes[str(o, "table")] ?: fail("no shape for ${str(o, "table")}")
            val incoming = recordFrom(o["incoming"]!!.jsonObject)
            val existing = rowFrom(o["existing"]!!.jsonObject)
            val want = o["expected"]!!.jsonObject
            assertEquals(
                str(want, "clashes") == "true",
                Rows.naturalKeyClash(shape, incoming, existing),
                "$id clashes",
            )
            assertEquals(
                str(want, "incomingLoses") == "true",
                Rows.incomingLosesClash(incoming, existing),
                "$id who loses",
            )
            n++
        }
        println("$n clash vectors")
    }

    @Test
    fun `applies deletions in the same order as the desktop`() {
        var n = 0
        for (c in cases("applyOrder")) {
            val o = c.jsonObject
            val records = o["records"]!!.jsonArray.map { recordFrom(it.jsonObject) }
            val want = o["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(want, Rows.deletionsFirst(records).map { it.uid }, str(o, "id"))
            n++
        }
        println("$n applyOrder vectors")
    }

    // ── the schema loader ───────────────────────────────────────────────────

    @Test
    fun `splits the schema on a whole separator line, not on the text`() {
        // The header of schema.sql explains the separator, so it contains the
        // separator. Splitting on the bare text cuts the header in half and
        // hands SQLite the tail of a comment. That mistake has been made once.
        val text = """
            -- ─── schema.sql ────────────────────────────────────────────────
            --   3. Statements are separated by a line containing only `-- @@`.
            --      The loaders split on that.

            CREATE TABLE a (id INTEGER);
            -- @@
            CREATE TRIGGER t AFTER INSERT ON a
            BEGIN
              UPDATE a SET id = 1;
            END;
            -- @@

            -- a chunk with nothing but comments in it
        """.trimIndent()

        val stmts = Schema.statements(text)
        assertEquals(2, stmts.size, "expected exactly the two real statements")
        assertTrue(stmts[0].startsWith("CREATE TABLE a"), "the header leaked into the first statement")
        assertTrue(stmts[1].contains("BEGIN"), "the trigger body was cut at its semicolons")
    }

    // ── the store's order of operations ─────────────────────────────────────

    @Test
    fun `looks for a duplicate before writing, and only where one is possible`() {
        val shapes = shapes()
        val budgets = shapes["budgets"] ?: fail("no budgets shape")
        val tasks = shapes["tasks"] ?: fail("no tasks shape")
        assertTrue(budgets.naturalKeys.isNotEmpty(), "budgets should have a natural key")
        assertTrue(tasks.naturalKeys.isEmpty(), "tasks should have no natural key but uid")

        val db = RecordingDb(shapes)
        drive {
            val store = SqlLocalStore.open(db, { "d-test" }, { "2026-08-15T12:00:00.000Z" })
            db.queried.clear()
            db.executed.clear()

            store.apply(
                listOf(
                    ChangeRecord(
                        "budgets", "u-budget", "2026-08-15T09:00:00.000Z", false, "d-other",
                        mapOf(
                            "category" to SyncValue.Text("food"),
                            "limit_amount" to SyncValue.Num(5000.0),
                            "month" to SyncValue.Text("2026-08"),
                        ),
                    ),
                    // Listed last but written first, because a delete frees the
                    // key a create needs.
                    ChangeRecord(
                        "tasks", "u-gone", "2026-08-15T09:00:00.000Z", true, "d-other",
                        mapOf("name" to SyncValue.Text("old event")),
                    ),
                    ChangeRecord(
                        "tasks", "u-task", "2026-08-15T09:00:00.000Z", false, "d-other",
                        mapOf("name" to SyncValue.Text("dailies")),
                    ),
                ),
            )
        }

        val clashQueries = db.queried.filter { it.sql.startsWith("SELECT * FROM") }
        assertEquals(1, clashQueries.size, "one clash query, for the one table that can clash")
        assertTrue(clashQueries[0].sql.contains("FROM budgets"), "the clash query hit the wrong table")
        assertTrue(
            clashQueries[0].sql.contains("category IS ? AND month IS ?"),
            "the clash query did not use the natural key",
        )
        // Two statements per row now: the spill goes first, then the row. Split
        // rather than counted, so that adding a third one day fails on the
        // property that matters instead of on an arithmetic that has to be kept
        // in step with the code it is testing.
        val upserts = db.executed.filter { it.sql.contains("ON CONFLICT(uid) DO UPDATE") }
        val spills = db.executed.filter { it.sql.contains("sync_spill") }
        // The clock is moved up to the newest stamp in the batch before any of
        // it is written, so that an edit made straight afterwards cannot be
        // stamped older than what it is editing. See SQL_SEEN.
        val clock = db.executed.filter { it.sql.contains("sync_clock") }
        assertEquals(3, upserts.size, "every row should have been written")
        assertEquals(3, spills.size, "every row should have had its spill settled")
        assertEquals(1, clock.size, "the clock should be moved once, not once per row")
        assertEquals(0, db.executed.indexOf(clock[0]), "the clock moves before anything is written")
        assertEquals(
            listOf(SyncValue.Text("2026-08-15T09:00:00.000Z")),
            clock[0].params,
            "the clock should be moved to the newest stamp in the batch",
        )
        assertEquals(
            db.executed.size,
            upserts.size + spills.size + clock.size,
            "an unexpected statement ran",
        )

        assertTrue(
            db.executed.indexOf(spills[0]) < db.executed.indexOf(upserts[0]),
            "the spill is written before the row, so a run dying between the two " +
                    "leaves a stale spill rather than a row missing its columns",
        )
        assertTrue(
            upserts[0].params.contains(SyncValue.Text("u-gone")),
            "the deletion should be written first, so it frees the key a create may need",
        )
    }

    @Test
    fun `reads the queue rather than a watermark, and empties it by version`() {
        val db = RecordingDb(shapes())
        drive {
            val store = SqlLocalStore.open(db, { "d-test" }, { "2026-08-16T03:00:00.000Z" })
            db.queried.clear()
            db.executed.clear()
            store.pending()
            store.settle(
                listOf(
                    ChangeRecord(
                        "tasks", "u-1", "2026-08-16T02:59:59.000Z", false, "d-test", emptyMap(),
                    ),
                ),
            )
        }

        val reads = db.queried.filter { it.sql.startsWith("SELECT r.*") }
        assertEquals(SYNCED_TABLES.size, reads.size, "one queue read per synced table")
        assertTrue(
            reads.all { it.sql.contains("JOIN sync_outbox o ON o.tbl =") },
            "the queue must be joined, not inferred from updated_at",
        )
        assertTrue(
            db.queried.none { it.sql.contains("WHERE updated_at > ?") },
            "a watermark read is a row list that another device's clock can move",
        )

        assertEquals(1, db.executed.size)
        val s = db.executed[0]
        assertTrue(s.sql.startsWith("DELETE FROM sync_outbox"), "settle must not touch the row")
        assertEquals(
            listOf(
                SyncValue.Text("tasks"),
                SyncValue.Text("u-1"),
                SyncValue.Text("2026-08-16T02:59:59.000Z"),
            ),
            s.params,
            "settling without the version clears an entry the trigger has already replaced",
        )
    }

    @Test
    fun `a delete writes a tombstone rather than removing the row`() {
        val db = RecordingDb(shapes())
        db.answer(
            "WHERE uid = ?",
            listOf(
                mapOf(
                    "id" to SyncValue.Num(3.0),
                    "uid" to SyncValue.Text("u-budget"),
                    "updated_at" to SyncValue.Text("2026-08-15T08:00:00.000Z"),
                    "deleted" to SyncValue.Num(0.0),
                    "category" to SyncValue.Text("food"),
                    "limit_amount" to SyncValue.Num(5000.0),
                    "month" to SyncValue.Text("2026-08"),
                    "currency" to SyncValue.Text("THB"),
                ),
            ),
        )

        drive {
            val store = SqlLocalStore.open(db, { "d-test" }, { "2026-08-15T12:00:00.000Z" })
            db.executed.clear()
            store.softDelete("budgets", "u-budget")
        }

        assertEquals(1, db.executed.size)
        val s = db.executed[0]
        assertTrue(s.sql.startsWith("INSERT INTO budgets"), "a delete must not be a DELETE")
        assertTrue(s.params.contains(SyncValue.Num(1.0)), "the tombstone flag was not set")
        // And the freed natural key, without which the same budget could never
        // be created again.
        assertTrue(
            s.params.contains(SyncValue.Text("deleted:u-budget")),
            "the tombstone is still holding its natural key",
        )
    }

    @Test
    fun `a column this schema cannot hold is kept beside the row, not dropped`() {
        val shape = shapes()["tasks"] ?: fail("no tasks shape")
        val known = ChangeRecord(
            "tasks", "u-1", "2026-08-18T03:00:00.000Z", false, "d-1",
            mapOf("name" to SyncValue.Text("เดินเล่น"), "mood_after" to SyncValue.Num(3.0)),
        )
        assertEquals(
            mapOf("mood_after" to SyncValue.Num(3.0)),
            Rows.unknownFields(shape, known),
            "only the column with nowhere to go",
        )

        // A tombstone has no body, so nothing may be re-attached to it later.
        val db = RecordingDb(shapes())
        drive {
            val store = SqlLocalStore.open(db, { "d-1" }, { "2026-08-18T03:00:00.000Z" })
            db.executed.clear()
            store.apply(listOf(known.copy(deleted = true)))
        }
        // Past the clock statement, which runs before any row is touched.
        val first = db.executed.first { !it.sql.contains("sync_clock") }
        assertTrue(
            first.sql.startsWith("DELETE FROM sync_spill"),
            "a deleted row must not keep columns beside it, got: ${first.sql}",
        )
    }
}