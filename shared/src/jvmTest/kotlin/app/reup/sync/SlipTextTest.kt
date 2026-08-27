// shared/src/jvmTest/kotlin/app/reup/sync/SlipTextTest.kt
//
// ─── SlipTextTest.kt — the half of slip reading that can be proved ───────────
//
// The text below is assembled by hand from the field shapes the desktop prompt
// spells out, which were themselves written against real slips: a K PLUS
// transfer and a Krungthai bill payment. It is NOT captured from a phone, and
// that is the one thing worth knowing about this file.
//
// So these hold the rules — the fee is not the amount, a Buddhist year is not
// the year, two candidates is a refusal — and they hold nothing at all about
// whether the recogniser hands the lines back in this shape. That question gets
// its answer the first time a real slip is read, and the screen that does it is
// built to show the raw text next to the reading for exactly that reason.

package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlipTextTest {

    private val transfer = """
        โอนเงินสำเร็จ
        22 ส.ค. 69 14:32 น.
        จาก
        นาย สมชาย ใจดี
        xxx-x-x1234-x
        ไปยัง
        ร้านกาแฟ ริมน้ำ
        xxx-x-x5678-x
        จำนวนเงิน 250.00 บาท
        ค่าธรรมเนียม 0.00 บาท
        เลขที่รายการ 015082266123456789
    """.trimIndent()

    private val bill = """
        ชำระบิลสำเร็จ
        วันที่ 01/08/2569
        ผู้ให้บริการ การไฟฟ้าส่วนภูมิภาค
        ยอดชำระ 1,284.50 บาท
        ยอดคงเหลือ 12,904.11 บาท
        รหัสอ้างอิง
        KTB2569080100987654
    """.trimIndent()

    @Test
    fun `the amount is the one that left the account`() {
        // Both of these print a second number underneath the first, and on one
        // of them that second number is larger. Taking the biggest would be
        // right on most slips and wrong on this one, every month.
        assertEquals(250.0, readSlip(transfer).amount)
        assertEquals(1284.5, readSlip(bill).amount, "a fee and a balance are not the amount")
    }

    @Test
    fun `two candidates with nothing to choose between them is a refusal`() {
        // A wrong number that looks right is worse than a blank somebody fills
        // in: a blank costs four taps, and a wrong amount is a month total that
        // is quietly off with nothing on any screen that says so.
        val noisy = readSlip(
            """
            สลิปโอนเงิน
            1,000.00 บาท
            500.00 บาท
            """.trimIndent(),
        )
        assertEquals(null, noisy.amount)
        assertTrue(noisy.problems.contains("amount-ambiguous"))
    }

    @Test
    fun `a labelled line outvotes a number that merely sits near the word baht`() {
        val reading = readSlip("บัญชี 1234567890 บาท\nจำนวนเงิน 99.00 บาท")
        assertEquals(99.0, reading.amount)
    }

    @Test
    fun `a buddhist year is not the year`() {
        // 2569 read as 2569 files the row five centuries out, and every total
        // it should have been part of stays quietly short.
        assertEquals("2026-08-22", readSlip(transfer).date, "two digits, Thai month")
        assertEquals("2026-08-01", readSlip(bill).date, "four digits, slashes")
        assertEquals("2026-08-22", readSlip("22 ส.ค. 2026").date, "already Gregorian, left alone")
    }

    @Test
    fun `the reference comes off its own label, beside it or under it`() {
        // A slip is covered in long digit strings — account numbers, phone
        // numbers, the QR payload. Taking the longest on the page would copy an
        // account number into a note about as often as it would find a
        // reference.
        assertEquals("015082266123456789", readSlip(transfer).reference)
        assertEquals("KTB2569080100987654", readSlip(bill).reference)
        assertEquals(null, readSlip("xxx-x-x1234-x\n0812345678").reference)
    }

    @Test
    fun `a page it cannot read says which parts, rather than inventing them`() {
        val empty = readSlip("ขอบคุณที่ใช้บริการ")
        assertEquals(null, empty.amount)
        assertEquals(null, empty.date)
        assertEquals(null, empty.currency)
        assertTrue(empty.problems.containsAll(listOf("no-amount", "no-currency", "no-date")))
    }

    /**
     * The first slip anybody actually read, copied off the phone character for
     * character, noise included.
     *
     * Three things in it were not guessed at beforehand: Thai digits come back
     * as Thai digits, zeros come back as the letter o, and a label can land on
     * one line with its number on another. All three are now handled, and this
     * is here so they stay handled.
     */
    private val realTopUp = listOf(
        "เติมเงินสำเร็จ                K",
        "27 ส.ค. 69 17:04 น.                +",
        "น.ส. ณัฐธยาน์ แ      Sea",
        "ธ.กสิกรไทย          ว",
        "พร้อมเพย์ อี-วอลเล็ต / o ii",
        "   006-oo\u0E53oo\u0E533650 |",
        "เลขที่รายการ:",
        "1 \u201Ca 2",
        "016239170445BPM19370    [\u0E52] ว i  [\u0E52]",
        "ss     จ   \u0E555   ete     แร \u0E53 แก้ญ ไร แนรย",
        "ค่าธรรมเนียม: ร ง Sizes [ต] ฟะเช  x",
        "ENS            0.00 บาท    สแกนตรวจสอบสลิป",
        "รายละเอียด: ณัฐธยาน์ แย้มหลั่งทรัพย์",
    ).joinToString("\n")

    @Test
    fun `a real slip, exactly as it came back`() {
        val r = readSlip(realTopUp)
        assertEquals("2026-08-27", r.date)
        assertEquals("THB", r.currency)
        assertEquals("016239170445BPM19370", r.reference, "two lines under its label, past the noise")
        // The amount is not on this page at all — the recogniser did not
        // return it. Saying so is the right answer; the fee sitting there at
        // 0.00 is not a substitute for it.
        assertEquals(null, r.amount)
        assertTrue(r.problems.contains("no-amount"))
    }

    @Test
    fun `a label on one line and its number on the next is still that label`() {
        // On the real slip the fee label came back with no digits on it and
        // its 0.00 arrived underneath. A fee that is not zero would otherwise
        // have been read as the amount, which is the one wrong answer that
        // costs money rather than a blank.
        val withFee = realTopUp.replace("0.00 บาท", "35.00 บาท")
        assertEquals(null, readSlip(withFee).amount)
    }

    @Test
    fun `thai digits are digits, and zeros read as letters are zeros`() {
        assertEquals(250.0, readSlip("จำนวนเงิน \u0E52\u0E55\u0E50.\u0E50\u0E50 บาท").amount)
        assertEquals(100.0, readSlip("จำนวนเงิน 1oo.oo บาท").amount)
        // But only inside something already numeric. A word that happens to
        // contain an o is a word.
        assertEquals(12.0, readSlip("Total Amount 12.00 บาท").amount)
    }

    @Test
    fun `baht is read from whichever way it was printed`() {
        assertEquals("THB", readSlip("จำนวนเงิน ฿250.00").currency)
        assertEquals("THB", readSlip("จำนวนเงิน 250.00 บาท").currency)
        assertEquals("THB", readSlip("Amount THB 250.00").currency)
        // Anything else is left for the form to decide, rather than assumed to
        // be baht because the phone is in Thailand.
        assertEquals(null, readSlip("Amount 250.00").currency)
    }
}