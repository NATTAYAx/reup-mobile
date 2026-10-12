// shared/src/jvmTest/kotlin/app/reup/sync/SlipInboxTest.kt
//
// ─── SlipInboxTest.kt — a slip that arrived on its own ───────────────────────
//
// The two real slips here are the first ones that ever came through a bank's
// album: a one-baht transfer each way between the same person's K PLUS and
// Krungthai accounts, with a memo typed at the bank, read by the recogniser and
// language data the app ships. Every name, account number, PromptPay number and
// reference in them is replaced with a made-up one of the same shape, as in
// SlipTextTest. This repository is public.
//
// They are also, by accident, the case this file most needs to get right: money
// that went from somebody to themselves, which is not spending.

package app.reup.sync

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlipInboxTest {

    // ── real, from the albums ────────────────────────────────────────────────

    /** K PLUS, KBank to the same person's PromptPay. Its 11 came back as Ti. */
    private val kplusMemo = listOf(
        "โอนเงินสําเร็จ",
        "|<+",
        "Ti ต.ค. 69 20.38น",
        "น.ส. สมศรี ใ",
        "ธ.กสิกรไทย",
        "THAILAND",
        "XXX-X-X4410-X",
        "ไทยพร้อมเป็นเจ้าภาพ",
        "ประชุบป:      ป     ซ์",
        "US. >",
        "ทศ:",
        "สละกลนรบาคารไดก U 2569",
        "ธรรลๆ",
        "น.ส. สมศรี ใจงาม",
        "รหัสพร้อมเพย์",
        "XXX-XXX-1207",
        "จํานวน:",
        "1.00 บาท",
        "ORO",
        "ค่าธรรมเนียม:",
        "ร a",
        "0.00 บาท",
        "เลขที่รายการ:",
        "OB",
        "สแกน",
        "016284110000APP01234",
        "ตรวจสอบสลิป",
        "บันทึกช่วยจํา: อาหารทดสอบ",
    ).joinToString("\n")

    /**
     * Krungthai NEXT, back the other way. Its loopless font made ต.ค. into
     * a.A., อ้างอิง into ฮ้างอิง, and the memo's จ่าย into ข่าย.
     */
    private val krungthaiMemo = listOf(
        "-",
        "\\",
        ">",
        "ค",
        "4",
        "=",
        "โอนเงินสําเรือ",
        "OF",
        "Te",
        "รหัสฮ้างอิง Bd51b156c29d65f1e",
        "ปฑ์",
        "rine",
        "| J",
        "ไซ",
        "Ts",
        "จาก",
        "* OK Ok",
        "น.ส. สมศรี ใ",
        "กรุงไทย",
        "XXX-X-XX1O2-3",
        "ไปยัง",
        "ย",
        "น.ส. สมศรี ใจงาม",
        "พร้อมเพย์",
        "XXX-XXXXXXXXK-0007",
        "จํานวนเงิน",
        "1.00 บาท",
        "ค่าธรรมเนียม",
        "0.00 บาท",
        "วันที่ทํารายการ",
        "11 a.A. 2569 - 20:39",
        "บันทึกชวยจํา",
        "ข่ายเงินทดสอบ",
    ).joinToString("\n")

    /**
     * Krungthai NEXT at 23:46 the same night, the first slip to arrive on its
     * own on the phone. The recogniser put a vowel into the payer's title that
     * the slip never printed — น.ุส. — read both of อ้างอิง's อ as ฮ, and the
     * memo's ท as เท: ทดสอบ101 came back เทดสอบ101. The reference is made up
     * in the shape the recogniser gave the real one, letters where 5s and 0s were.
     */
    private val krungthaiSelf = listOf(
        "“น",
        "ว",
        "~",
        "4",
        "=",
        "ye\"",
        "OF",
        "โอนเงินสําเรือ",
        "at",
        "รหัสฮ้างฮิง BdhxOcT9e2ffa7cb1",
        "fan",
        "lr",
        "or",
        "จาก",
        "©",
        "* kK Ok",
        "น.ุส. สมศรี ใ",
        "กรุงไทย",
        "XXX-X-XX1O2-3",
        "ไปยัง",
        "ฯ",
        "น.ส. สมศรี ใจงาม",
        "พร้อมเพย์",
        "XXX-XXXXXXXXK-0007",
        "จํานวนเงิน",
        "1.00 บาท",
        "ค่าธรรมเนียม",
        "0.00 บาท",
        "วันที่ทํารายการ",
        "11 ต.ค. 2569 - 23:46",
        "บันทึกชวยจํา",
        "เทดสอบ101",
    ).joinToString("\n")

    // ── hand-made ────────────────────────────────────────────────────────────

    /** Somebody else, everything readable. The case one press is for. */
    private val lunch = """
        โอนเงินสำเร็จ
        11 ต.ค. 69 12:05 น.
        น.ส. สมศรี ใ
        ธ.กสิกรไทย
        XXX-X-X4410-X
        นาย สมชาย ดีมาก
        ธ.กรุงไทย
        XXX-X-X5678-X
        จำนวน:
        45.00 บาท
        ค่าธรรมเนียม:
        0.00 บาท
        เลขที่รายการ:
        016284120500APP09876
        บันทึกช่วยจำ: อาหาร ข้าวมันไก่
    """.trimIndent()

    private val categories = listOf(
        Category("food", "🍜", null),
        Category("transport", "🚌", null),
        Category("bills", "🧾", null),
        Category("game", "🎮", null),
        Category("other", "📦", null),
        Category("cat_pets", "🐱", "แมว"),
        Category("phone_bill", "📱", "ค่าบิลมือถือ"),
    )

    private val today = LocalDate(2026, 10, 11)

    private fun at(h: Int, m: Int, day: Int = 11) = LocalDateTime(2026, 10, day, h, m, 12)

    private fun draft(raw: String, savedAt: LocalDateTime): SlipDraft {
        val r = readSlip(raw, SlipHints(homeCurrency = "THB"))
        return slipDraft(r, raw, savedAt, memoCategory(r.memo, categories))
    }

    // ── the memo names the category ──────────────────────────────────────────

    @Test
    fun `the memo's first word names the category, with or without a space`() {
        assertEquals(MemoSplit("food", "ทดสอบ"), memoCategory("อาหารทดสอบ", categories), "what was really typed")
        assertEquals(MemoSplit("food", "ข้าวมันไก่"), memoCategory("อาหาร ข้าวมันไก่", categories))
        assertEquals(MemoSplit("food", "ข้าวมันไก่"), memoCategory("อาหาร: ข้าวมันไก่", categories))
        assertEquals(MemoSplit("food", ""), memoCategory("อาหาร", categories), "the category and nothing else")
        assertEquals(MemoSplit("cat_pets", "ทรายแมว"), memoCategory("แมว ทรายแมว", categories), "a name somebody made")
        assertEquals(MemoSplit("game", "เติมเพชร"), memoCategory("เกม เติมเพชร", categories), "half of เกม/กาชา")
        assertEquals(MemoSplit("food", "lunch with A"), memoCategory("food lunch with A", categories), "a key that is a word")
    }

    @Test
    fun `a memo that names no category is kept whole`() {
        assertEquals(MemoSplit(null, "ข้าวมันไก่"), memoCategory("ข้าวมันไก่", categories))
        assertEquals(MemoSplit(null, "foodcourt"), memoCategory("foodcourt", categories), "food does not end there")
        assertEquals(MemoSplit(null, ""), memoCategory(null, categories))
        assertEquals(MemoSplit(null, ""), memoCategory("   ", categories))
    }

    @Test
    fun `the longest name wins`() {
        assertEquals(MemoSplit("phone_bill", "dtac"), memoCategory("ค่าบิลมือถือ dtac", categories))
        assertEquals(MemoSplit("bills", "ไฟ"), memoCategory("ค่าบิล ไฟ", categories))
    }

    @Test
    fun `a category name misread the way loopless letters are is still the category`() {
        assertEquals(MemoSplit("food", "ข้าวมันไก่"), memoCategory("ฮาหาร ข้าวมันไก่", categories), "อ read as ฮ")
        assertEquals(MemoSplit("bills", "ไฟ"), memoCategory("คาบิล ไฟ", categories), "the tone lost")
        assertEquals(MemoSplit("food", "ทดสอบ"), memoCategory("ฮาหารทดสอบ", categories), "no space, as typed")
        assertEquals(MemoSplit(null, "ฮาร์ดดิสก์"), memoCategory("ฮาร์ดดิสก์", categories), "a word that only starts the same")
    }

    // ── who it went to ───────────────────────────────────────────────────────

    @Test
    fun `money moved between one's own accounts is not spending`() {
        assertTrue(paidToSelf(kplusMemo), "K PLUS cuts the payer after the surname's first letter")
        assertTrue(paidToSelf(krungthaiMemo), "Krungthai prints stars, which the recogniser dropped")
        assertTrue(paidToSelf("จาก น.ส. สมศรี ใ***\nไปยัง น.ส. สมศรี ใจงาม"), "stars, and the side on the line")
        assertFalse(paidToSelf(lunch), "somebody else")
        assertFalse(paidToSelf("น.ส. สมศรี ใ\nร้านกาแฟ ริมน้ำ"), "a shop has no title")
        // A first name on its own is shared by half the country.
        assertFalse(paidToSelf("น.ส. สมศรี\nน.ส. สมศรี ใจงาม"))
        assertFalse(paidToSelf("น.ส. สมศรี ใ"), "one person on the page")
    }

    @Test
    fun `what the recogniser adds to a name or takes from it does not hide who was paid`() {
        assertTrue(paidToSelf(krungthaiSelf), "น.ุส. is still น.ส.")
        assertTrue(paidToSelf("น.ส. สมศรี ใ\nนางสาว สมศรี ใจงาม"), "one bank's น.ส. is another's นางสาว")
        assertTrue(paidToSelf("น.ส สมศรี ใ\nน.ส. สมศรี ใจงาม"), "a dot lost")
        assertTrue(paidToSelf("น.ส. สมศร ใ\nน.ส. สมศรี ใจงาม"), "a vowel lost from the name")
        assertFalse(paidToSelf("น.ส. สมศรี ใ\nน.ส. สมศรี ดีมาก"), "the same first name, somebody else")
        assertFalse(paidToSelf("น.ส. สมศรี ใ\nนาย สมศรี ใจงาม"), "the title is part of who it is")
        assertFalse(paidToSelf("Mr. John S\nMrt station John Smith"), "mrt is not mr")
    }

    // ── the two real slips ───────────────────────────────────────────────────

    @Test
    fun `the real K PLUS slip, dated by the file because the slip's own date was unreadable`() {
        val r = readSlip(kplusMemo, SlipHints(homeCurrency = "THB"))
        assertEquals(1.0, r.amount)
        assertEquals("THB", r.currency)
        assertEquals("016284110000APP01234", r.reference)
        assertEquals("อาหารทดสอบ", r.memo)
        assertNull(r.date, "Ti ต.ค. 69 is no date")

        val d = draft(kplusMemo, at(20, 38))
        assertEquals("2026-10-11", d.date, "20.38 on the slip, 20:38 on the file")
        assertEquals("20:38", d.clock)
        assertEquals("food", d.category)
        assertEquals("ทดสอบ", d.note)
        assertFalse("no-date" in d.problems, "settled, so not a problem any more")
        assertFalse("date-from-file" in d.problems)
        assertTrue(d.toSelf)
        assertFalse(d.oneTap, "a transfer to oneself is never one press")
    }

    @Test
    fun `the real Krungthai slip`() {
        val r = readSlip(krungthaiMemo, SlipHints(homeCurrency = "THB"))
        assertEquals(1.0, r.amount)
        assertEquals("Bd51b156c29d65f1e", r.reference)
        // Pinned as the recogniser reads it today, so a better reading shows up
        // here as a change rather than passing unnoticed.
        assertEquals("ข่ายเงินทดสอบ", r.memo)

        val d = draft(krungthaiMemo, at(20, 39))
        assertEquals("2026-10-11", d.date)
        assertEquals("20:39", d.clock)
        assertEquals(null, d.category)
        assertEquals("ข่ายเงินทดสอบ", d.note)
        assertTrue(d.toSelf)
        assertFalse(d.oneTap)
    }

    @Test
    fun `the real Krungthai slip that arrived on its own`() {
        val r = readSlip(krungthaiSelf, SlipHints(homeCurrency = "THB"))
        assertEquals(1.0, r.amount)
        assertEquals("2026-10-11", r.date, "ต.ค. read right this time")
        assertEquals("BdhxOcT9e2ffa7cb1", r.reference, "beside รหัสฮ้างฮิง")
        // The page's reading, pinned. The phone reads this line a second time
        // on its own and keeps ทดสอบ101; SlipTextTest has that half.
        assertEquals("เทดสอบ101", r.memo)

        val d = draft(krungthaiSelf, at(23, 46))
        assertEquals("2026-10-11", d.date)
        assertTrue(d.toSelf, "the phone offered one press here, which was the bug")
        assertFalse(d.oneTap)
        assertEquals("โอนเข้าบัญชีตัวเอง · ฿1", slipFace(d, "อื่นๆ", today).title)
    }

    // ── one press ────────────────────────────────────────────────────────────

    @Test
    fun `a payment to somebody else with nothing left to check is one press`() {
        val d = draft(lunch, at(12, 5))
        assertTrue(d.oneTap, "problems were " + d.problems)
        assertEquals("2026-10-11", d.date)
        val row = slipExpense(d)
        assertEquals(
            ExpenseDraft(
                amount = "45",
                currency = "THB",
                category = "food",
                note = "ข้าวมันไก่",
                date = "2026-10-11",
                slipRef = "016284120500APP09876",
            ),
            row,
        )
        assertEquals(emptyList(), expenseProblems(row, categories.map { it.key }), "the form's own rules agree")
    }

    @Test
    fun `no date and no matching time is the file's date, and waits for a person`() {
        val raw = "โอนเงินสำเร็จ\nนาย สมชาย ดีมาก\nจำนวนเงิน 45.00 บาท"
        val d = draft(raw, at(12, 5))
        assertEquals("2026-10-11", d.date)
        assertTrue("date-from-file" in d.problems)
        assertFalse(d.oneTap)
        // The same slip saved again from the bank's history hours later: the
        // time on it no longer matches the file, so nothing confirms the day.
        val later = draft("$raw\nเวลา 08:10 น.", at(12, 5))
        assertTrue("date-from-file" in later.problems)
        assertFalse(later.oneTap)
    }

    @Test
    fun `a slip whose date is days away from the file waits for a person`() {
        val raw = "โอนเงินสำเร็จ\n8 ต.ค. 69 12:05 น.\nจำนวนเงิน 45.00 บาท"
        val d = draft(raw, at(9, 0))
        assertEquals("2026-10-08", d.date, "the slip's own date, not the file's")
        assertTrue("date-not-file-day" in d.problems)
        assertFalse(d.oneTap)
    }

    @Test
    fun `midnight is one transfer, not two days`() {
        // Paid at 23:59, saved at 00:01: the printed date is the day before.
        val printed = draft("โอนเงินสำเร็จ\n10 ต.ค. 69 23:59 น.\nจำนวนเงิน 45.00 บาท", at(0, 1))
        assertEquals("2026-10-10", printed.date)
        assertFalse("date-not-file-day" in printed.problems)
        // No readable date, and the time is just before midnight: the day before.
        val before = draft("โอนเงินสำเร็จ\nTi ต.ค. 69 23:59 น.\nจำนวนเงิน 45.00 บาท", at(0, 2))
        assertEquals("2026-10-10", before.date)
        // And a bank clock a little ahead of the phone's, the other way.
        val after = draft("โอนเงินสำเร็จ\nTi ต.ค. 69 00:01 น.\nจำนวนเงิน 45.00 บาท", at(23, 58, day = 10))
        assertEquals("2026-10-11", after.date)
    }

    @Test
    fun `a reading the rules were not sure of is never one press`() {
        val saved = at(12, 5)
        val split = MemoSplit("food", "")
        val base = SlipReading(amount = 45.0, currency = "THB", date = "2026-10-11", reference = "016284120500APP09876")
        assertTrue(slipDraft(base, "", saved, split).oneTap)
        assertFalse(slipDraft(base.copy(problems = listOf("amount-unsure")), "", saved, split).oneTap)
        assertFalse(slipDraft(base.copy(currency = null, problems = listOf("no-currency")), "", saved, split).oneTap)
        assertFalse(slipDraft(base.copy(amount = null, problems = listOf("no-amount")), "", saved, split).oneTap)
        // A missing reference only means a second copy cannot be recognised.
        assertTrue(slipDraft(base.copy(reference = null, problems = listOf("no-reference")), "", saved, split).oneTap)
    }

    // ── the words ────────────────────────────────────────────────────────────

    @Test
    fun `what the notification says`() {
        val one = draft(lunch, at(12, 5))
        assertEquals(SlipFace("ข้าวมันไก่ · −฿45", "อาหาร · วันนี้ 12:05"), slipFace(one, "อาหาร", today))

        val self = draft(kplusMemo, at(20, 38))
        val face = slipFace(self, "อาหาร", today)
        assertEquals("โอนเข้าบัญชีตัวเอง · ฿1", face.title)
        assertTrue(face.text.startsWith("ไม่นับเป็นรายจ่าย"), face.text)

        val unsure = draft("โอนเงินสำเร็จ\nนาย สมชาย ดีมาก\nจำนวนเงิน 45.00 บาท", at(12, 5))
        assertEquals(slipCheck(unsure), slipFace(unsure, "อื่นๆ", today).text)
        assertEquals("อื่นๆ · −฿45", slipFace(unsure, "อื่นๆ", today).title, "no memo: the category is the title")
    }

    // ── the two questions the phone asks the books ───────────────────────────

    private class Asked(val sql: String, val params: List<SyncValue>)

    private class FakeDb(private val answers: Map<String, List<DbRow>>) : Db {
        val asked = mutableListOf<Asked>()
        override suspend fun execute(sql: String, params: List<SyncValue>) {
            error("nothing here writes")
        }
        override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> {
            asked += Asked(sql, params)
            return answers.entries.firstOrNull { sql.contains(it.key) }?.value ?: emptyList()
        }
    }

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
        check(finished) { "the block actually suspended; the fake never does" }
        thrown?.let { throw it }
    }

    @Test
    fun `asks whether a slip is already a row, and where a note went last time`() {
        val db = FakeDb(
            mapOf(
                "slip_ref = ?" to listOf(mapOf("n" to SyncValue.Num(1.0))),
                "note = ?" to listOf(mapOf("category" to SyncValue.Text("food"))),
            ),
        )
        var seen = false
        var category: String? = null
        var blank: String? = "unset"
        drive { seen = MoneyRepo(db).slipRecorded("016284110000APP01234") }
        drive { category = MoneyRepo(db).categoryUsedFor(" ข้าวมันไก่ ") }
        drive { blank = MoneyRepo(db).categoryUsedFor("   ") }
        assertTrue(seen)
        assertEquals("food", category)
        assertNull(blank, "an empty note is nobody's habit")
        assertEquals(2, db.asked.size, "and is never asked about")
        assertEquals(SQL_SLIP_RECORDED, db.asked[0].sql)
        assertEquals(listOf(SyncValue.Text("016284110000APP01234")), db.asked[0].params)
        assertEquals(SQL_CATEGORY_FOR_NOTE, db.asked[1].sql)
        assertEquals(listOf(SyncValue.Text("ข้าวมันไก่")), db.asked[1].params)
        assertTrue(SQL_CATEGORY_FOR_NOTE.contains("category != 'other'"), "the fallback is nobody's choice")
    }
}
