package app.reup.sync

// ─── SqlBind.kt — the one thing Android's SQLite cannot say ──────────────────
//
// WHY THIS EXISTS
//
// SqlLocalStore looks for a duplicate before writing by asking
//
//     SELECT * FROM budgets WHERE cat IS ? AND month IS ?
//
// `IS` rather than `=` on purpose: a natural key can legitimately contain a
// null, and `= NULL` matches nothing while `IS NULL` matches the rows that have
// one. The desktop binds null straight through and gets that.
//
// Android cannot. `SQLiteDatabase.rawQuery` takes a `String[]`, and every
// element goes through `SQLiteProgram.bindString`, which throws on null:
//
//     java.lang.IllegalArgumentException: the bind value at index 1 is null
//
// There is no overload that accepts a nullable argument. This is a hole in the
// platform API, not a thing the caller is doing wrong.
//
// WHY THE FIX IS HERE AND NOT IN AndroidDb.kt
//
// AndroidDb is deliberately the smallest thing that could work, because it is
// the one file no test in this repo can reach. A scanner that walks a string
// tracking quote state is exactly the kind of code that has edge cases, so
// putting it there would mean the riskiest logic sitting in the least tested
// file. Rewriting a query is a decision; handing bytes to a driver is not.
//
// WHY NOT CHANGE THE SQL THE STORE GENERATES
//
// Those statements are compared against the desktop's, string for string, by
// StoreTest. Making the phone generate something different to dodge a platform
// limitation would break the one guarantee that says both devices ask the same
// questions. The statement is unchanged; only the way it is handed over is.

object SqlBind {

    /** A statement and the parameters that are still bound, after nulls became
     *  literals. Nothing in [params] is [SyncValue.Null]. */
    data class Bound(val sql: String, val params: List<SyncValue>)

    /**
     * Replace the placeholders whose value is null with the literal `NULL`, and
     * return the parameters that survive.
     *
     * Only the four characters `NULL` are ever written into the statement, so
     * this cannot become a way for a value to reach the parser. Everything that
     * is not null stays a bound parameter exactly as before.
     *
     * Quote state is tracked because a `?` inside a string literal is a question
     * mark and not a placeholder. SQLite escapes a quote by doubling it, which
     * this handles without a special case: the first closes the string and the
     * second immediately opens it again, so the character after the pair is read
     * as being inside, which it is.
     *
     * More placeholders than parameters is treated as null rather than as an
     * error. That case means the caller is already wrong, and the useful failure
     * is SQLite's own message about the statement, not one from here about the
     * count.
     *
     * There is no fast path for "no nulls anywhere", although it is by far the
     * common case and returning the string unchanged is obviously cheaper. A
     * shortcut would be a second behaviour to keep in agreement with the first,
     * and the first version of this file had exactly that bug: the shortcut
     * skipped the scan, so a statement with more placeholders than parameters
     * came back untouched down one path and rewritten down the other. Scanning a
     * statement of a few dozen characters is not worth owning two answers.
     */
    fun inlineNulls(sql: String, params: List<SyncValue>): Bound {
        val out = StringBuilder(sql.length + 8)
        val kept = mutableListOf<SyncValue>()
        var next = 0
        var inString = false

        for (ch in sql) {
            when {
                inString -> {
                    out.append(ch)
                    if (ch == '\'') inString = false
                }
                ch == '\'' -> {
                    out.append(ch)
                    inString = true
                }
                ch == '?' -> {
                    val v = params.getOrNull(next)
                    next++
                    if (v == null || v is SyncValue.Null) {
                        out.append("NULL")
                    } else {
                        out.append('?')
                        kept += v
                    }
                }
                else -> out.append(ch)
            }
        }

        return Bound(out.toString(), kept)
    }
}