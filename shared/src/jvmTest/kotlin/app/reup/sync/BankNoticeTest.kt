// shared/src/jvmTest/kotlin/app/reup/sync/BankNoticeTest.kt
//
// ─── BankNoticeTest.kt — money coming in, off a bank's notification ──────────
//
// The notices here are written the way the real ones were on a phone's shade
// on 11 October 2026: K PLUS's รายการเงินเข้า and รายการโอน/ถอน, and
// Krungthai NEXT's รับเงินสำเร็จ. Every account number in them is made up, in
// the same shape as the real one, and matches the made-up accounts the slip
// tests use. This repository is public.
//
// All four real notices that day were one person moving one baht between
// their own two banks and back - which is the thing this file most has to get
// right. None of them is income.

package app.reup.sync

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankNoticeTest {

    private val kplus = "com.kasikorn.retail.mbanking.wap"
    private val ktb = "ktbcs.netbank"

    /** The person's K PLUS account, as K PLUS masks it. Made up. */
    private val mine = "xxx-x-x4410-x"

    /** The same account as Krungthai prints it, where money came from. */
    private val mineAtKtb = "XXX-X-XX410-7"

    /** Somebody else's account at the same bank. */
    private val theirs = "XXX-X-XX993-1"

    private val today = LocalDate(2026, 10, 11)

    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime(2026, 10, 11, h, m, s)

    /** Epoch milliseconds on the same made-up day: only the differences matter. */
    private fun ms(h: Int, m: Int, s: Int = 0): Long = 1_791_700_000_000L + ((h * 60L + m) * 60L + s) * 1000L

    private fun kplusIn(clock: String, amount: String = "1.00", account: String = mine) =
        "บัญชี $account จำนวนเงิน $amount บาท วันที่ 11 ต.ค. 69 $clock น."

    private fun ktbIn(amount: String = "1.00", from: String = "กสิกรไทย $mineAtKtb") =
        "ได้รับ +$amount บาท เข้าพร้อมเพย์ จากบัญชี $from"

    // ── reading ──────────────────────────────────────────────────────────────

    @Test
    fun kplusMoneyIn() {
        val n = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39"), at(20, 39, 41)))
        assertEquals("kbank", n.bank)
        assertEquals(Flow.IN, n.flow)
        assertEquals(1.0, n.amount)
        assertEquals(mine, n.account)
        assertNull(n.fromBank)
        assertNull(n.fromAccount)
        // The notice's own minute, not when the phone got round to posting it.
        assertEquals(at(20, 39), n.at)
    }

    @Test
    fun kplusMoneyOut() {
        val n = assertNotNull(bankNotice(kplus, "รายการโอน/ถอน", kplusIn("20:38"), at(20, 38, 20)))
        assertEquals(Flow.OUT, n.flow)
        assertEquals(mine, n.account)
        assertEquals(at(20, 38), n.at)
    }

    @Test
    fun krungthaiMoneyIn() {
        val n = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn(), at(20, 38, 25)))
        assertEquals("ktb", n.bank)
        assertEquals(Flow.IN, n.flow)
        assertEquals(1.0, n.amount)
        assertNull(n.account)
        assertEquals("kbank", n.fromBank)
        assertEquals(mineAtKtb, n.fromAccount)
        // Krungthai prints no time, so the post's is the notice's.
        assertEquals(at(20, 38, 25), n.at)
    }

    @Test
    fun amountsWithCommasAndSatang() {
        assertEquals(1250.5, bankNotice(kplus, "รายการเงินเข้า", kplusIn("09:05", "1,250.50"), at(9, 5))?.amount)
        assertEquals(12000.0, bankNotice(ktb, "รับเงินสำเร็จ", ktbIn("12,000.00"), at(9, 5))?.amount)
        assertEquals(35.0, bankNotice(ktb, "รับเงินสำเร็จ", "ได้รับ 35 บาท", at(9, 5))?.amount)
        // Nothing is not an amount.
        assertNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("09:05", "0.00"), at(9, 5)))
    }

    @Test
    fun aSenderWithoutABankIsStillMoneyIn() {
        val n = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn(from = "พร้อมเพย์ XXX-XXX-1207"), at(9, 5)))
        assertNull(n.fromBank)
        assertEquals("XXX-XXX-1207", n.fromAccount)
        val bare = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", "ได้รับ +1.00 บาท เข้าพร้อมเพย์", at(9, 5)))
        assertNull(bare.fromAccount)
    }

    @Test
    fun everythingElseIsLeftAlone() {
        // Another app saying the same words: LINE's bank accounts, Gmail.
        assertNull(bankNotice("jp.naver.line.android", "รายการเงินเข้า", kplusIn("20:39"), at(20, 39)))
        assertNull(bankNotice("com.google.android.gm", "รายการเงินเข้า", kplusIn("20:39"), at(20, 39)))
        // A title nobody has seen: never guessed at.
        assertNull(bankNotice(kplus, "โปรโมชันพิเศษ", kplusIn("20:39"), at(20, 39)))
        assertNull(bankNotice(kplus, "รับสิทธิ์พิเศษ", kplusIn("20:39"), at(20, 39)))
        // Least of all one that talks about money arriving in words not seen
        // yet: read as money going out, it would hide the arrival it is.
        assertNull(bankNotice(kplus, "ได้รับการโอนเงิน", kplusIn("20:39"), at(20, 39)))
        assertNull(bankNotice(kplus, "มีเงินโอนเข้า", kplusIn("20:39"), at(20, 39)))
        assertEquals(Flow.OUT, bankNotice(kplus, "ชำระเงินสำเร็จ", kplusIn("20:39"), at(20, 39))?.flow)
        // Krungthai's own app is read for money in only.
        assertNull(bankNotice(ktb, "โอนเงินสำเร็จ", "โอน 1.00 บาท", at(20, 39)))
        // A body in a shape the rules do not know, or none.
        assertNull(bankNotice(kplus, "รายการเงินเข้า", "มีรายการเข้าบัญชีของคุณ", at(20, 39)))
        assertNull(bankNotice(kplus, "รายการเงินเข้า", null, at(20, 39)))
        assertNull(bankNotice(ktb, "รับเงินสำเร็จ", "", at(20, 39)))
    }

    @Test
    fun spacesOfEveryKind() {
        val odd = "บัญชี $mine\nจำนวน​เงิน  1.00 บาท\nวันที่ 11 ต.ค.69 20.39 น."
        val n = assertNotNull(bankNotice(kplus, " รายการเงินเข้า ", odd, at(20, 40)))
        assertEquals(1.0, n.amount)
        assertEquals(at(20, 39), n.at)
    }

    @Test
    fun yearsAsPrinted() {
        val long = "บัญชี $mine จำนวนเงิน 1.00 บาท วันที่ 11 ต.ค. 2569 20:39 น."
        assertEquals(at(20, 39), bankNotice(kplus, "รายการเงินเข้า", long, at(20, 40))?.at)
        // A printed date a year away from the post is a date read wrong: the
        // post's own is used instead.
        val off = "บัญชี $mine จำนวนเงิน 1.00 บาท วันที่ 11 ต.ค. 68 20:39 น."
        assertEquals(at(20, 40, 5), bankNotice(kplus, "รายการเงินเข้า", off, at(20, 40, 5))?.at)
        // Just after midnight, about just before it: still the bank's day.
        val late = "บัญชี $mine จำนวนเงิน 1.00 บาท วันที่ 10 ต.ค. 69 23:59 น."
        assertEquals(LocalDateTime(2026, 10, 10, 23, 59), bankNotice(kplus, "รายการเงินเข้า", late, at(0, 0, 4))?.at)
    }

    // ── accounts ─────────────────────────────────────────────────────────────

    @Test
    fun twoMasksOfOneAccount() {
        assertTrue(sameAccount(mine, mineAtKtb))
        assertTrue(sameAccount(mineAtKtb, mine))
        assertTrue(sameAccount(mine, mine.uppercase()))
        assertFalse(sameAccount(mine, theirs))
        // Laid out differently: a phone number is not an account.
        assertFalse(sameAccount("XXX-X-XX120-7", "XXX-XXX-1207"))
        // Two digits in common prove nothing.
        assertFalse(sameAccount("xxx-x-xxx10-x", mineAtKtb))
        assertFalse(sameAccount("xxx-x-xxxxx-x", mineAtKtb))
        assertFalse(sameAccount(null, mine))
        assertFalse(sameAccount(mine, "บัญชี"))
    }

    @Test
    fun banksByName() {
        assertEquals("kbank", bankKey("กสิกรไทย"))
        assertEquals("kbank", bankKey("ธ.กสิกรไทย"))
        assertEquals("kbank", bankKey("K PLUS"))
        assertEquals("ktb", bankKey("กรุงไทย"))
        assertEquals("ktb", bankKey("Krungthai NEXT"))
        assertEquals("ktb", bankKey("NEXT"))
        assertEquals("scb", bankKey("ไทยพาณิชย์"))
        assertEquals("bbl", bankKey("กรุงเทพ"))
        assertEquals("bay", bankKey("กรุงศรีอยุธยา"))
        assertNull(bankKey("พร้อมเพย์"))
        assertNull(bankKey("Screenshots"))
        assertNull(bankKey(""))
        assertNull(bankKey(null))
    }

    // ── which is which ───────────────────────────────────────────────────────

    /** The four notices of the day, and the slip Krungthai saved for the money it sent. */
    private fun theDay(): Triple<List<Arrival>, List<Movement>, Map<String, List<String>>> {
        val kplusOut = assertNotNull(bankNotice(kplus, "รายการโอน/ถอน", kplusIn("20:38"), at(20, 38, 20)))
        val ktbIn = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn(), at(20, 38, 25)))
        val back = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39"), at(20, 39, 41)))
        val again = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("23:46"), at(23, 46, 30)))
        val arrivals = listOf(
            arrivalOf(ktbIn, ms(20, 38, 25)),
            arrivalOf(back, ms(20, 39, 41)),
            arrivalOf(again, ms(23, 46, 30)),
        )
        val slip = SlipDraft(1.0, "THB", "2026-10-11", "20:39", "BdhxOcT9e2ffa7cb1", null, "", null, true, emptyList())
        val slip2 = slip.copy(clock = "23:46", reference = "Ac1f9e07d3b52c8a4")
        val movements = listOfNotNull(
            movementOf(kplusOut, ms(20, 38, 20)),
            // The K PLUS slip for the first transfer, read off its album.
            slipMovement(41L, ms(20, 38, 22), "K PLUS", slip.copy(clock = "20:38", reference = "Bd51b156c29d65f1e")),
            slipMovement(42L, ms(20, 39, 38), "Krungthai NEXT", slip),
            slipMovement(43L, ms(23, 46, 27), "Krungthai NEXT", slip2),
        )
        return Triple(arrivals, movements, mapOf("kbank" to listOf(mine)))
    }

    @Test
    fun aDayOfMovingMoneyBetweenTwoBanksIsNoIncome() {
        val (arrivals, movements, own) = theDay()
        val verdicts = judgeArrivals(arrivals, movements, own)
        assertEquals(3, verdicts.size)
        for (a in arrivals) assertEquals(Verdict.Own, verdicts[a.id], a.date + " " + a.clock + " " + a.bank)
    }

    @Test
    fun theAccountAloneIsEnough() {
        // Krungthai's notice on its own, before any slip has been read: the
        // account it came from is one K PLUS has printed as the person's.
        val (arrivals, _, own) = theDay()
        val first = arrivals.first()
        assertEquals(Verdict.Own, judgeArrivals(listOf(first), emptyList(), own)[first.id])
        // Without knowing that account, the same notice is money from somebody.
        assertEquals(Verdict.Income, judgeArrivals(listOf(first), emptyList(), emptyMap())[first.id])
    }

    @Test
    fun theSlipAloneIsEnough() {
        // K PLUS says nothing about where money came from; Krungthai's slip to
        // the same person, for the same baht, a few seconds earlier does.
        val (arrivals, movements, _) = theDay()
        val back = arrivals[1]
        val verdicts = judgeArrivals(listOf(back), movements, emptyMap())
        assertEquals(Verdict.Own, verdicts[back.id])
        assertEquals(Verdict.Income, judgeArrivals(listOf(back), emptyList(), emptyMap())[back.id])
    }

    @Test
    fun moneyFromSomebodyElseStays() {
        val friend = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn("350.00", "กสิกรไทย $theirs"), at(12, 0, 10)))
        val pay = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("12:30", "1,500.00"), at(12, 30, 5)))
        val arrivals = listOf(arrivalOf(friend, ms(12, 0, 10)), arrivalOf(pay, ms(12, 30, 5)))
        val (_, movements, own) = theDay()
        val verdicts = judgeArrivals(arrivals, movements, own)
        assertEquals(Verdict.Income, verdicts[arrivals[0].id])
        assertEquals(Verdict.Income, verdicts[arrivals[1].id])
    }

    @Test
    fun moneyLeavingAnotherBankAtTheSameMomentIsAMaybe() {
        // 500 out of K PLUS and 500 into Krungthai two minutes later, from an
        // account nothing has said is the person's: probably theirs, not sure.
        val out = assertNotNull(bankNotice(kplus, "รายการโอน/ถอน", kplusIn("10:00", "500.00"), at(10, 0, 5)))
        val inn = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn("500.00", "กสิกรไทย $theirs"), at(10, 2, 0)))
        val move = movementOf(out, ms(10, 0, 5))
        val a = arrivalOf(inn, ms(10, 2, 0))
        assertEquals(Verdict.Maybe(move.id), judgeArrivals(listOf(a), listOf(move), emptyMap())[a.id])
        assertNotNull(arrivalCheck(Verdict.Maybe(move.id)))
        // Eleven minutes apart, it is somebody else's money.
        val late = arrivalOf(inn, ms(10, 11, 6))
        assertEquals(Verdict.Income, judgeArrivals(listOf(late), listOf(move), emptyMap())[late.id])
    }

    @Test
    fun moneyThatLeftAndCameBackToOneAccountIsTwoThings() {
        // Out of the K PLUS account and into the same one a minute later: a
        // refund, a friend paying back. Not the same money.
        val out = assertNotNull(bankNotice(kplus, "รายการโอน/ถอน", kplusIn("20:38"), at(20, 38, 20)))
        val back = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39"), at(20, 39, 41)))
        val a = arrivalOf(back, ms(20, 39, 41))
        assertEquals(Verdict.Income, judgeArrivals(listOf(a), listOf(movementOf(out, ms(20, 38, 20))), emptyMap())[a.id])
        // Between two of the person's K PLUS accounts it may be the same money.
        val other = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39", account = "xxx-x-x7731-x"), at(20, 39, 41)))
        val b = arrivalOf(other, ms(20, 39, 41))
        assertTrue(judgeArrivals(listOf(b), listOf(movementOf(out, ms(20, 38, 20))), emptyMap())[b.id] is Verdict.Maybe)
        // And a K PLUS slip says nothing about money arriving at K PLUS.
        val slip = SlipDraft(1.0, "THB", "2026-10-11", "20:38", null, null, "", null, true, emptyList())
        val m = assertNotNull(slipMovement(7L, ms(20, 38, 22), "K PLUS", slip))
        assertEquals(Verdict.Income, judgeArrivals(listOf(a), listOf(m), emptyMap())[a.id])
    }

    @Test
    fun oneMovementExplainsOneArrival() {
        val slip = SlipDraft(1.0, "THB", "2026-10-11", "20:40", null, null, "", null, true, emptyList())
        val m = assertNotNull(slipMovement(9L, ms(20, 40, 0), "Krungthai NEXT", slip))
        val first = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39"), at(20, 39, 30)))
        val second = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:41"), at(20, 41, 30)))
        val a = arrivalOf(first, ms(20, 39, 30))
        val b = arrivalOf(second, ms(20, 41, 30))
        val verdicts = judgeArrivals(listOf(b, a), listOf(m), emptyMap())
        // The nearer one is the slip's; the other is money from somebody.
        assertEquals(Verdict.Own, verdicts[a.id])
        assertEquals(Verdict.Income, verdicts[b.id])
    }

    @Test
    fun howFarApartIsTooFar() {
        val self = SlipDraft(1.0, "THB", "2026-10-11", "08:00", null, null, "", null, true, emptyList())
        val m = assertNotNull(slipMovement(5L, ms(8, 0), "Krungthai NEXT", self))
        val n = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("08:15"), at(8, 15)))
        val inside = arrivalOf(n, ms(8, 15))
        val outside = arrivalOf(n, ms(8, 15, 1))
        assertEquals(Verdict.Own, judgeArrivals(listOf(inside), listOf(m), emptyMap())[inside.id])
        assertEquals(Verdict.Income, judgeArrivals(listOf(outside), listOf(m), emptyMap())[outside.id])
        // A different amount is a different transfer, however close.
        val other = arrivalOf(assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("08:00", "2.00"), at(8, 0))), ms(8, 0, 3))
        assertEquals(Verdict.Income, judgeArrivals(listOf(other), listOf(m), emptyMap())[other.id])
    }

    @Test
    fun slipsAsMovements() {
        val d = SlipDraft(45.0, "THB", "2026-10-11", "12:00", null, null, "", null, false, emptyList())
        val m = assertNotNull(slipMovement(3L, ms(12, 0), "K PLUS", d))
        assertEquals("s3", m.id)
        assertEquals("kbank", m.bank)
        assertFalse(m.toSelf)
        assertNull(slipMovement(3L, ms(12, 0), "K PLUS", d.copy(currency = "USD")))
        assertNull(slipMovement(3L, ms(12, 0), "K PLUS", d.copy(amount = null)))
        assertNull(slipMovement(3L, ms(12, 0), "Camera", d)?.bank)
    }

    // ── keeping one notice once ──────────────────────────────────────────────

    @Test
    fun theSameNoticeHasTheSameId() {
        val one = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39"), at(20, 39, 41)))
        // Posted again on reconnecting, seconds later: K PLUS's own minute is the same.
        val again = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:39"), at(20, 39, 59)))
        assertEquals(noticeId(one), noticeId(again))
        val later = assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("20:40"), at(20, 40, 2)))
        assertNotEquals(noticeId(one), noticeId(later))
        val out = assertNotNull(bankNotice(kplus, "รายการโอน/ถอน", kplusIn("20:39"), at(20, 39, 41)))
        assertNotEquals(noticeId(one), noticeId(out))
        // Krungthai prints no time; two of its notices a minute apart are two.
        val k1 = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn(), at(20, 38, 25)))
        val k2 = assertNotNull(bankNotice(ktb, "รับเงินสำเร็จ", ktbIn(), at(20, 39, 25)))
        assertNotEquals(noticeId(k1), noticeId(k2))
        // The id is what is kept, and it carries no account number.
        assertTrue(noticeId(one).startsWith("n"))
        assertFalse(noticeId(one).contains("4410"))
        assertTrue(noticeKey(one).contains("4410"))
    }

    // ── the words ────────────────────────────────────────────────────────────

    @Test
    fun theRowAndWhatItSaves() {
        val (arrivals, _, _) = theDay()
        val fromKbank = arrivals[0]
        val back = arrivals[1]
        assertEquals(SlipFace("เงินเข้า · +฿1", "K PLUS · วันนี้ 20:39"), arrivalFace(back, Verdict.Income, today))
        assertEquals(
            SlipFace("เงินเข้า · +฿1", "Krungthai NEXT จากกสิกรไทย · วันนี้ 20:38"),
            arrivalFace(fromKbank, Verdict.Income, today),
        )
        assertEquals(
            SlipFace("เงินเข้า · +฿1", "อาจเป็นเงินโอนจากบัญชีตัวเอง เช็คก่อนบันทึก"),
            arrivalFace(back, Verdict.Maybe("n1"), today),
        )
        assertEquals("K PLUS · เมื่อวาน 20:39", arrivalFace(back, Verdict.Income, LocalDate(2026, 10, 12)).text)

        assertEquals("เข้ากสิกรไทย", arrivalNote(back))
        assertEquals("เข้ากรุงไทย จากกสิกรไทย", arrivalNote(fromKbank))
        val draft = arrivalIncome(back)
        assertEquals("1", draft.amount)
        assertEquals("", draft.source)
        assertEquals("2026-10-11", draft.date)
        assertEquals("THB", draft.currency)
        assertTrue(incomeProblems(draft).isEmpty())

        val big = arrivalOf(assertNotNull(bankNotice(kplus, "รายการเงินเข้า", kplusIn("09:05", "1,250.50"), at(9, 5))), ms(9, 5))
        assertEquals("1250.5", arrivalIncome(big).amount)
        assertEquals("เงินเข้า · +฿1,250.50", arrivalFace(big, Verdict.Income, today).title)
    }

    @Test
    fun theSettingsLine() {
        val (arrivals, _, _) = theDay()
        val back = arrivals[1]
        assertEquals("วันนี้ 20:39 · K PLUS +฿1", arrivalLine(back, Verdict.Income, today))
        assertEquals("วันนี้ 20:39 · K PLUS +฿1 · โอนจากบัญชีตัวเอง ไม่นับ", arrivalLine(back, Verdict.Own, today))
        assertEquals("วันนี้ 20:39 · K PLUS +฿1 · รอเช็ค", arrivalLine(back, Verdict.Maybe("n1"), today))
        assertNull(arrivalCheck(Verdict.Income))
        assertNull(arrivalCheck(Verdict.Own))
    }

    @Test
    fun moneyInThatCouldNotBeRead() {
        // Said by its title, whatever the body turns out to be.
        assertTrue(looksLikeMoneyIn(kplus, "รายการเงินเข้า"))
        assertTrue(looksLikeMoneyIn(ktb, "รับเงินสำเร็จ"))
        assertFalse(looksLikeMoneyIn(kplus, "รายการโอน/ถอน"))
        assertFalse(looksLikeMoneyIn(kplus, "โปรโมชันพิเศษ"))
        assertFalse(looksLikeMoneyIn(kplus, null))
        assertFalse(looksLikeMoneyIn("jp.naver.line.android", "รายการเงินเข้า"))
        assertEquals("วันนี้ 20:39 · K PLUS · มีเงินเข้าแต่อ่านยอดไม่ออก", missedLine("kbank", at(20, 39, 41), today))
        assertEquals(
            "เมื่อวาน 09:05 · Krungthai NEXT · มีเงินเข้าแต่อ่านยอดไม่ออก",
            missedLine("ktb", LocalDateTime(2026, 10, 10, 9, 5), today),
        )
    }
}
