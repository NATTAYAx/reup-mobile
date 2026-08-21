package app.reup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.reup.sync.Db
import app.reup.sync.DbRow
import app.reup.sync.SqlBind
import app.reup.sync.SyncValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─── AndroidDb.kt — the only part of storage that is Android ─────────────────
//
// Everything about what a row means, which statements run, and in what order
// lives in the shared module and is tested on the JVM. This file forwards two
// calls to SQLiteDatabase and converts values. It is deliberately the smallest
// thing that could work, because it is the one piece no test here can reach.
//
// The file lives beside the database on internal storage, which on Android means
// a directory no other app can read without root. There is no extra encryption
// at rest: the threat that would need it is someone holding an unlocked phone,
// and at that point the app is open too.

class AndroidDb private constructor(private val db: SQLiteDatabase) : Db {

    override suspend fun execute(sql: String, params: List<SyncValue>) =
        withContext(Dispatchers.IO) {
            val b = SqlBind.inlineNulls(sql, params)
            db.execSQL(b.sql, b.params.map(::raw).toTypedArray())
        }

    override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> =
        withContext(Dispatchers.IO) {
            // rawQuery has no way to bind null: every element of its String[]
            // goes through bindString, which throws rather than binding NULL.
            // SqlBind turns those placeholders into the literal the statement
            // meant anyway. See SqlBind.kt for why it is not solved here.
            val b = SqlBind.inlineNulls(sql, params)
            val out = mutableListOf<DbRow>()
            db.rawQuery(b.sql, b.params.map { textOf(it) }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val row = HashMap<String, SyncValue>(c.columnCount)
                    for (i in 0 until c.columnCount) {
                        row[c.getColumnName(i)] = when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> SyncValue.Null
                            android.database.Cursor.FIELD_TYPE_INTEGER ->
                                SyncValue.Num(c.getLong(i).toDouble())
                            android.database.Cursor.FIELD_TYPE_FLOAT ->
                                SyncValue.Num(c.getDouble(i))
                            // A blob has no place in this schema, and reading one
                            // as text would corrupt it quietly. Better to see the
                            // column arrive empty than to see it arrive wrong.
                            android.database.Cursor.FIELD_TYPE_BLOB -> SyncValue.Null
                            else -> SyncValue.Text(c.getString(i))
                        }
                    }
                    out += row
                }
            }
            out
        }

    /**
     * No `else` branch, on purpose.
     *
     * SyncValue is sealed, so a case added to it in the shared module breaks
     * this file at compile time instead of falling into a default that guesses.
     * That is what caught Bool the first time this module was ever compiled.
     */
    private fun raw(v: SyncValue): Any? = when (v) {
        is SyncValue.Text -> v.value
        is SyncValue.Num -> v.value
        // SQLite has no boolean. The desktop stores these flags as 1 and 0, and
        // binding a Kotlin Boolean would let the driver decide the encoding.
        is SyncValue.Bool -> if (v.value) 1L else 0L
        SyncValue.Null -> null
    }

    /**
     * rawQuery binds everything as a string, which is not a shortcut.
     *
     * SQLite compares by column affinity, so a number bound as "5" against an
     * INTEGER column still matches. Every parameter this app binds is a uid, a
     * timestamp or a settings key, all of them text already.
     */
    private fun textOf(v: SyncValue): String = when (v) {
        is SyncValue.Text -> v.value
        is SyncValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString() else v.value.toString()
        // "1" and "0" rather than "true" and "false": affinity makes the string
        // match an INTEGER column, and the words match nothing at all.
        is SyncValue.Bool -> if (v.value) "1" else "0"
        // Unreachable: SqlBind.inlineNulls removes these before anything is
        // bound. It throws rather than returning a placeholder string, because
        // a null that reached here would mean the rewriter stopped working and
        // the wrong thing to do is paper over it with "" or "null".
        SyncValue.Null -> error("null reached the binder; SqlBind.inlineNulls should have removed it")
    }

    companion object {
        const val FILE = "reup.db"

        private var instance: AndroidDb? = null

        /**
         * One connection for the whole process.
         *
         * Every screen and every receiver that touches storage goes through
         * here rather than opening its own. Two SQLiteDatabase handles on one
         * file are legal and mostly work, which is the problem: they take
         * separate locks, so the failure they produce is an intermittent
         * "database is locked" during a sync rather than something that shows
         * up the first time anyone tries.
         *
         * The application context, never an Activity's — this outlives every
         * screen, and holding an Activity here would hold the whole view tree
         * with it for as long as the process lives.
         */
        fun shared(ctx: Context): AndroidDb =
            instance ?: open(ctx.applicationContext).also { instance = it }

        /**
         * Context.openOrCreateDatabase, not SQLiteDatabase.openOrCreateDatabase.
         *
         * They look interchangeable and are not. The static one takes a File and
         * will not create the directory the file goes in, and on a fresh install
         * `databases/` does not exist yet — so the very first launch, and only
         * the first launch, would fail with "unable to open database file". The
         * Context method creates the directory, which is the entire difference
         * and the whole reason it is the one used here.
         */
        fun open(ctx: Context): AndroidDb =
            AndroidDb(ctx.openOrCreateDatabase(FILE, Context.MODE_PRIVATE, null))
    }
}