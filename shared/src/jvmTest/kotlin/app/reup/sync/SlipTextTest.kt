// shared/src/jvmTest/kotlin/app/reup/sync/SlipTextTest.kt
//
// ─── SlipTextTest.kt — the half of slip reading that can be proved ───────────
//
// Three kinds of text in here, and which kind a test uses is the first thing
// to know about it.
//
// HAND-MADE. Assembled from the field shapes the desktop prompt spells out, and
// for other countries from how their slips print money, dates and labels. These
// hold the RULES — the fee is not the amount, a Buddhist year is not the year,
// two candidates is a refusal — and say nothing about what a recogniser returns.
//
// REAL. Copied off the phone, or captured from the same recogniser and language
// data the app ships, character for character with the noise left in. A slip
// invented by hand never has "ie. 7" in the middle of it.
//
// COLOUR. The same October slips the way the app used to read them, in colour,
// before SlipImage turned them black and white. Two of them came back with
// amounts that were wrong and looked right. They are kept for what the rules
// must never do with them, whatever the picture: file a wrong number.
//
// Every name, account number, wallet number and reference in the real slips is
// replaced with a made-up one of the same shape. This repository is public, and
// a slip is a page of somebody's bank statement.

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
        val noisy = readSlip("สลิปโอนเงิน\n1,000.00 บาท\n500.00 บาท")
        assertEquals(null, noisy.amount)
        assertTrue(noisy.problems.contains("amount-ambiguous"))
    }

    @Test
    fun `a labelled line outvotes a number that merely sits near the word baht`() {
        assertEquals(99.0, readSlip("บัญชี 1234567890 บาท\nจำนวนเงิน 99.00 บาท").amount)
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

    // ── the first real slip ──────────────────────────────────────────────────
    //
    // Three things in it were not guessed at beforehand: Thai digits come back
    // as Thai digits, zeros come back as the letter o, and a label can land on
    // one line with its number on another. All three are handled, and this is
    // here so they stay handled.

    private val realTopUp = listOf(
        "เติมเงินสำเร็จ                K",
        "27 ส.ค. 69 17:04 น.                +",
        "น.ส. สมศรี ใ      Sea",
        "ธ.กสิกรไทย          ว",
        "พร้อมเพย์ อี-วอลเล็ต / o ii",
        "   006-oo๓oo๓1207 |",
        "เลขที่รายการ:",
        "1 “a 2",
        "016239170000BPM12345    [๒] ว i  [๒]",
        "ss     จ   ๕5   ete     แร ๓ แก้ญ ไร แนรย",
        "ค่าธรรมเนียม: ร ง Sizes [ต] ฟะเช  x",
        "ENS            0.00 บาท    สแกนตรวจสอบสลิป",
        "รายละเอียด: สมศรี ใจงาม",
    ).joinToString("\n")

    @Test
    fun `a real slip, exactly as it came back`() {
        val r = readSlip(realTopUp)
        assertEquals("2026-08-27", r.date)
        assertEquals("THB", r.currency)
        assertEquals("016239170000BPM12345", r.reference, "two lines under its label, past the noise")
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
        assertEquals(250.0, readSlip("จำนวนเงิน ๒๕๐.๐๐ บาท").amount)
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

    // ── the second real slip ─────────────────────────────────────────────────
    //
    // Same bank, same app, one week later. The amount came back this time, and
    // the reference moved: four lines of noise between the label and the number.

    private val secondRealSlip = listOf(
        "เติมเงินสำเร็จ",
        "<X+",
        "30 ส.ค. 69 15:35 น.",
        "น.ส. สมศรี ใ",
        "ธ.กสิกรไทย",
        "%%%-%-%4410-%",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "7 !",
        "ie. 7",
        "oe",
        "016242150000123456",
        "1",
        "aa",
        "ae",
        "จำนวน:",
        "=.",
        "Pn โซ",
        "a",
        "2.00 บาท",
        "a",
        "เฉตต",
        "แซชน",
        "ซะ",
        "a",
        ".ค่าธรรมเนียม:",
        "OF",
        "แล",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
    ).joinToString("\n")

    @Test
    fun `the second real slip reads all four fields`() {
        val r = readSlip(secondRealSlip)
        assertEquals(2.0, r.amount)
        assertEquals("THB", r.currency)
        assertEquals("2026-08-30", r.date)
        assertEquals("016242150000123456", r.reference)
        assertEquals(emptyList(), r.problems)
    }

    @Test
    fun `the fee is still not the amount when it is three lines below its label`() {
        // If the exclusion missed it, this would read a two baht top-up as a
        // zero baht one, or refuse to read it at all.
        val changed = secondRealSlip.replace("0.00 บาท", "35.00 บาท")
        assertEquals(2.0, readSlip(changed).amount)
    }

    @Test
    fun `the account number is not mistaken for the reference`() {
        // 100234567 is a wallet id five lines above the label. A rule that took
        // the longest digit run on the page would take it on some other slip,
        // which is why this one only looks below a label.
        assertEquals("016242150000123456", readSlip(secondRealSlip).reference)
    }

    // ── the third real slip, where the recogniser lost the reference ─────────
    //
    // Amount, currency and date came back right. The reference did not come
    // back at all — it was returned as Thai syllables. Kept because it is the
    // case the rules must NOT solve: there is nothing left of the number to
    // find, and any rule that produced one would be producing it out of noise.

    private val thirdRealSlip = listOf(
        "เติมเงินสำเร็จ",
        "31 ส.ค. 69 13:39 น.",
        "<X+",
        "น.ส. สมศรี ใ",
        "ธ.กสิกรไทย",
        "6",
        "%%%-%-%4410-%",
        "ง/",
        "[Prompt] Pay",
        "พร้อมเพย์ อี-วอลเล็ต / G-Wallet iil",
        "006-xXxxxxXxxx-1207",
        "เลขที่รายการ:",
        "เพียฟี",
        "หด รร",
        "เพย |",
        "|",
        "ง",
        "%",
        "พ =",
        "a",
        ": 70.60 บาท",
        "cat",
        "o4",
        "ir",
        "Pagitats",
        "ค่าธรรมเนียม:",
        "0.00 as",
        "สแกนตรวจสอบสลิป",
        "รายละเอียด: สมศรี ใจงาม",
    ).joinToString("\n")

    @Test
    fun `the third real slip reads the money and admits the reference is gone`() {
        val r = readSlip(thirdRealSlip)
        assertEquals(70.6, r.amount)
        assertEquals("THB", r.currency)
        assertEquals("2026-08-31", r.date)
        assertEquals(null, r.reference)
        // A blank field and "problems none" side by side is the screen saying
        // nothing is wrong while something is.
        assertEquals(listOf("no-reference"), r.problems)
    }

    @Test
    fun `the wallet number above the label is not taken as the reference`() {
        assertEquals(null, readSlip(thirdRealSlip).reference)
    }

    @Test
    fun `an unlabelled amount still wins when the fee lost its currency word`() {
        // จำนวน: was lost and its 70.60 came back with a bare colon, while the
        // fee kept its label and its 0.00 came back as "0.00 as".
        assertEquals(70.6, readSlip(thirdRealSlip).amount)
    }

    // ── the fourth real slip, where a bare digit almost became the amount ────
    //
    // The third slip again, read after the image was enlarged. Everything got
    // worse: the date came back as ว1สาดต=ออ9ร, the 70.60 as จ9, and the
    // reference in two halves. Kept because the rules once read `จ9` as nine
    // baht, said nothing was wrong, and offered to file it.

    private val fourthRealSlip = listOf(
        "เติมเงินสำเร็จ",
        "ว1สาดต=ออ9ร ววะบ.",
        "<+",
        "น.ส. สมศรี ใ",
        "ธ.กสิกรไทย",
        "XXX-X-x4/10-xX",
        ">              ๑        4% <         4",
        "1",
        "พร้อมเพย์ อี-วอลเล็ต / G-Wallet",
        "Prompt}",
        "Pay",
        "006-%%%%%%%-1207",
        "เลขที่รายการ:",
        "ยย",
        "01624313000 7BRMOGI45",
        "aes",
        "พยูย",
        "& a",
        "ยย",
        "จำนวน:  .",
        "จ9",
        "๒",
        "X",
        "๒ | a",
        "แูย",
        "น",
        "a",
        "เว",
        "ธรรมเมื ยม",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
        "รายละเอียด: สมศรี ใจงาม",
    ).joinToString("\n")

    @Test
    fun `a bare digit under an amount label is not an amount`() {
        val r = readSlip(fourthRealSlip)
        assertEquals(null, r.amount)
        assertTrue(r.problems.contains("no-amount"), "problems were " + r.problems)
    }

    @Test
    fun `half a reference number is refused rather than shortened`() {
        // Eleven characters of a twenty-character reference, indistinguishable
        // from a real one once it has been written into a note.
        assertEquals(null, readSlip(fourthRealSlip).reference)
    }

    @Test
    fun `a destroyed date is admitted, not guessed`() {
        val r = readSlip(fourthRealSlip)
        assertEquals(null, r.date)
        assertTrue(r.problems.contains("no-date"), "problems were " + r.problems)
    }

    @Test
    fun `the currency survives because one word of it did`() {
        // บาท is still on the fee line, which is the only reason this field is
        // not null too: currency is the weakest of the four, not the strongest.
        assertEquals("THB", readSlip(fourthRealSlip).currency)
    }

    // ── October: five slips, black and white ─────────────────────────────────
    //
    // What tha+eng tessdata_fast returns for five real slips once SlipImage has
    // turned them black and white. Four K PLUS top-ups and one Pao Tang
    // payment, all from the same evening.

    private val octoberWallet = listOf(
        "เติมเงินสําเร็จ",
        "8 ต.ค. 69 18:10 น.",
        "I<+",
        "น.ส. สมศรี ใ",
        "6",
        "โร",
        "ธ.กสิกรไทย",
        "%%%-%-%4410-%",
        "พร้อมเพย์ อี-วอลเล็ต / G-Wallet",
        "[๐ทาม1",
        "รืะมตะ",
        "006-%%%%%%%%-1207",
        "เลขที่รายการ:",
        "016281180000APM01111",
        "จํานวน:",
        "52.00 บาท",
        "ห",
        "ค่าธรรมเนียม:",
        "[=]:",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
        "รายละเอียด: สมศรี ใจงาม",
    ).joinToString("\n")

    private val octoberGrab17 = listOf(
        "เติมเงินสําเร็จ",
        "|<+",
        "8 ต.ค. 69 18:08 น.",
        "น.ส. สมศรี ใ",
        "LS",
        "———",
        "ธ.กสิกรไทย",
        "%%%-%-%4410-%",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "016281180000222222",
        "ORR",
        "ไจ",
        "oom",
        "จํานวน:",
        "oan",
        "neo",
        "a",
        "a",
        "17.00 บาท",
        "+: ต",
        "se",
        "ค่าธรรมเนียม:",
        "a",
        "a",
        "a",
        "a",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
    ).joinToString("\n")

    private val octoberGrab270 = listOf(
        "เติมเงินสําเร็จ",
        "8 ต.ค. 69 14:42 น.",
        "|<+",
        "ธ.กสิกรไทย",
        "%%%-%-%4410-%",
        "LS",
        "———",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "=i",
        "016281140000333333",
        "ae a eee ปิง",
        "oom",
        "จํานวน:",
        "เล็ แกซิต",
        "พสะชี",
        "ไพลิ ป",
        "อ ย ไป",
        "สย",
        "270.00 บาท",
        "ล ย",
        "พร5 ชีนป",
        "se",
        "ค่าธรรมเนียม:",
        "= ๒",
        "สย",
        "=o",
        "ซะ",
        "a",
        "a",
        "a",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
    ).joinToString("\n")

    private val octoberGrab6 = listOf(
        "เติมเงินสําเร็จ",
        "|<+",
        "8 ต.ค. 69 16:27 น.",
        "น.ส. สมศรี ใ",
        "LS",
        "———",
        "ธ.กสิกรไทย",
        "%%%-%-%4410-%",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "พชีย",
        "016281160000444444",
        "OR",
        "A, ย",
        "ri",
        "oom",
        "จํานวน:",
        "oan",
        "neo",
        "a",
        "ae",
        "a",
        "6.00 บาท",
        "|",
        "=",
        "ค่าธรรมเนียม:",
        "L",
        "ซะ",
        "a",
        "a",
        "a",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
    ).joinToString("\n")

    private val paoTang = listOf(
        "WAT",
        "ne",
        "-ัโณยย์9",
        "เปข้าตัง",
        "“= 60/40",
        "@ กํารายการสําเร็จ",
        "รหัสอ้างอิง 5fOe2d3c-4b5a-4c6d-8e7f-001122334",
        "8 ต.ค. 2569 18:10 น.",
        "สมศรี u***",
        "G",
        "G-Wallet ID: **** ******* 1207",
        "ง",
        "ร้านตัวอย่าง",
        "ซื",
        "qo",
        "อาหาร ของหวาน เครื่องคื่ม",
        "ค่าสินค้า/บริการ",
        "130 บาท",
        "สิทธิไทยช่วยไทยพลัส",
        "-78 บาท",
        "จํานวนเงินที่ชําระ",
        "52  บาท",
    ).joinToString("\n")

    @Test
    fun `the october top-ups read all four fields`() {
        val expected = listOf(
            Triple(octoberWallet, 52.0, "016281180000APM01111"),
            Triple(octoberGrab17, 17.0, "016281180000222222"),
            Triple(octoberGrab270, 270.0, "016281140000333333"),
            Triple(octoberGrab6, 6.0, "016281160000444444"),
        )
        for ((text, amount, reference) in expected) {
            val r = readSlip(text)
            assertEquals(amount, r.amount, "amount of $reference")
            assertEquals("THB", r.currency)
            assertEquals("2026-10-08", r.date)
            assertEquals(reference, r.reference)
            assertEquals(emptyList(), r.problems, "problems of $reference")
        }
    }

    @Test
    fun `the label can be six lines above its amount`() {
        // 270.00 arrives with five lines of rubbish between it and จำนวน:,
        // and the fee's 0.00 with seven under ค่าธรรมเนียม:. A window of four,
        // which is what the rules used to look through, owns neither.
        assertEquals(270.0, readSlip(octoberGrab270.replace("\n0.00 บาท", "\n35.00 บาท")).amount)
    }

    @Test
    fun `pao tang is read as what was paid, not the price or the subsidy`() {
        // 130 for the food, -78 from the government scheme, 52 out of the
        // wallet. The ledger wants 52.
        val r = readSlip(paoTang)
        assertEquals(52.0, r.amount)
        assertEquals("THB", r.currency)
        assertEquals("2026-10-08", r.date)
        // Pao Tang prints its reference cut short with "…" to fit the line.
        // What is on the slip is not the reference, so nothing is copied.
        assertEquals(null, r.reference)
        assertEquals(listOf("reference-cut"), r.problems)
    }

    @Test
    fun `without the paid line the price is still not the amount`() {
        // If จำนวนเงินที่ชำระ is lost, what is left is a price and a subsidy,
        // and neither of them is what left the wallet.
        val r = readSlip(paoTang.replace("จํานวนเงินที่ชําระ\n52  บาท", ""))
        assertEquals(null, r.amount)
    }

    // ── October in colour: what the rules must never do ──────────────────────
    //
    // The same slips read the way the app read them until this round. Before
    // the rules below, the seventeen baht top-up was read as 170 and the two
    // hundred and seventy as 7.

    private val colourWallet = listOf(
        "เตมเงนสาเรจ",
        "ไ|<+",
        "8 ต.ุค. 69 18:10 น",
        "น.ส.ุสมศรีใ",
        "ธ.กสิกรไทย",
        "XXX-X-X4410-x",
        "ครือนเพล์",
        "พร้อมเพย์ อี-วอลเลิต / G-Wallet",
        "เอทาวน",
        "ij",
        "006-%%%%%%%%-1207",
        "เลขที่รายการ:",
        "ee areas",
        "จํานวน:",
        "เววจ",
        "ง XK",
        "52 001 บาท",
        "ie",
        "3",
        " ค่าธรรมเนียม:",
        "[=]:",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
        "รายละเอยด: สมศรี ใจงาม",
    ).joinToString("\n")

    private val colourGrab17 = listOf(
        "เติมเงินสําเร็จ",
        "ไ|<+",
        "8 ต.ค. 69 18:08 น.",
        "น.ส. สมศรี ใ",
        "ธ.กสิกรไทย",
        "๒@",
        "XXX-X-X4410-x",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "ORR",
        "Seer:",
        "ไจ",
        "เพพ ส!",
        "จํานวน:",
        "one",
        "eae",
        "พพ ป",
        "a",
        "+: a.",
        "es",
        ": 1700 บาท",
        "Le",
        "a",
        "คําธรรม เนียม:",
        "a",
        "สพ",
        "สแกนตรวจสอบสลิป",
        "0.00 บาท",
    ).joinToString("\n")

    private val colourGrab270 = listOf(
        "เติมเงินสําเร็จ",
        "8 ต.ุค. 69 14:42 น.",
        "ไ|<+",
        "ธ.กสิกรไทย",
        "XXX-X-x4/410-x",
        "Y",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "=i",
        "พตี eS,",
        "eA tacts scl ae",
        "พสพ",
        "จํานวน:",
        "แล็ แกชด",
        "meee",
        "a",
        "เส พ ไพ",
        "ส ย",
        "ย ย",
        "๒ร5 ปแฟ็",
        "a",
        "270.00 ue",
        "= ๒",
        "สย",
        "ส ย",
        "ซะ",
        "a",
        "a",
        "ลําธรรม oo ม:",
        "สพ",
        "สแกนตรวจสอบสลิป",
        "0.00 บาท 7",
    ).joinToString("\n")

    private val colourGrab6 = listOf(
        "เติมเงินสําเร็จ",
        "ไ|<+",
        "8 ต.ุค. 69 16:27 น.",
        "น.ส. สมศรี ใ",
        "ธ.กสิกรไทย",
        "๒@",
        "XXX-X-X4410-x",
        "ง/",
        "GrabPay Wallet",
        "100234567",
        "เลขที่รายการ:",
        "สขีย",
        "OF",
        "A ล",
        "Seah Say ee See og kate",
        "ri",
        "am",
        "จํานวน:",
        "one",
        "eae",
        "พพ ป",
        "เชียป",
        "a",
        "|",
        "=",
        ": 6.00 บาท",
        "L",
        "ซะ",
        "Le",
        "a",
        "คําธรรม เนียม:",
        "สพ",
        "สแกนตรวจสอบสลิป",
        "0.00 บาท",
    ).joinToString("\n")

    private val colourPaoTang = listOf(
        "Wan",
        "KI”",
        "ne",
        "ชี",
        "เป้าตัง",
        "2",
        "=~ 60/40",
        "@ กํารายการสําเร็จ",
        "รหัสอ้างอิง ล๕10๕ลสทีล-๒11ส่-4b5a-4c6ล-001122334.",
        "8 GA. 2569 18:10 น",
        "สมศรี u***",
        "G",
        "G-Wallet ID: **** ******* 1207",
        "ง,",
        "ร้านตัวอย่าง",
        "ลจวบ",
        "อาหาร ของหวาน เครื่องดืม",
        "ค่าสินค้า/บริการ",
        "130 บาท",
        "สิทธิมทยช่วยไทยพลัส",
        "-78 บาท",
        "จํานวนเงินที่ชําระ",
        "52  บาท",
    ).joinToString("\n")

    @Test
    fun `seventeen baht read as 1700 is refused, not filed`() {
        // The point between 17 and 00 was lost. 1700 is not impossible on its
        // own; it is impossible on a page whose fee is printed 0.00.
        val r = readSlip(colourGrab17)
        assertEquals(null, r.amount)
        assertTrue(r.problems.contains("amount-unsure"), "problems were " + r.problems)
    }

    @Test
    fun `a stray digit after the fee is not the amount`() {
        // `0.00 บาท 7`. บาท belongs to the number before it, never after.
        val r = readSlip(colourGrab270)
        assertEquals(null, r.amount)
        assertTrue(r.problems.contains("no-amount"), "problems were " + r.problems)
    }

    @Test
    fun `52 point 00 broken by a space is neither half`() {
        val r = readSlip(colourWallet)
        assertEquals(null, r.amount)
        assertTrue(r.problems.contains("amount-unsure"), "problems were " + r.problems)
    }

    @Test
    fun `a vowel the slip never printed does not hide the month`() {
        // `8 ต.ุค. 69`: a sara u under the ต that is not on the slip.
        assertEquals("2026-10-08", readSlip(colourWallet).date)
        assertEquals("2026-10-08", readSlip(colourGrab270).date)
        assertEquals("2026-10-08", readSlip(colourGrab6).date)
    }

    @Test
    fun `the colour readings that were right stay right`() {
        assertEquals(6.0, readSlip(colourGrab6).amount)
        assertEquals(52.0, readSlip(colourPaoTang).amount)
    }

    // ── other countries ──────────────────────────────────────────────────────
    //
    // Hand-made, from how each place prints money. The point is the rules, not
    // any one bank: every case here is one way of writing a number, a currency,
    // a date or a label that the Thai-only version would have misread or missed.

    @Test
    fun `numbers are read the way each country writes them`() {
        val cases = listOf(
            "Betrag 1.234,56 €" to 1234.56,
            "Montant 1 234,56 €" to 1234.56,
            "Total CHF 1'234.50" to 1234.5,
            "Amount ₹1,23,456.00" to 123456.0,
            "Jumlah Rp 150.000" to 150000.0,
            "Số tiền 50.000 VND" to 50000.0,
            "合計 ¥1,200" to 1200.0,
            "금액 12,000원" to 12000.0,
            "Total KWD 12.345" to 12.345,
            "Total 1,234" to 1234.0,
            "Amount US$ 1,250.00" to 1250.0,
            "Tổng cộng 1.250.000đ" to 1250000.0,
        )
        for ((text, amount) in cases) assertEquals(amount, readSlip(text).amount, text)
    }

    @Test
    fun `a space is a thousands separator only where the currency uses one`() {
        // In Paris 1 120,00 is one number. In a Bangkok receipt `1 120.00` is a
        // quantity and a price, and joining them files eleven hundred baht for
        // a plate of rice.
        assertEquals(1120.0, readSlip("Total 1 120,00 €").amount)
        val rice = readSlip("ข้าวผัด 1 120.00 บาท")
        assertEquals(null, rice.amount)
        assertTrue(rice.problems.contains("amount-unsure"), "problems were " + rice.problems)
    }

    @Test
    fun `a currency sign says which currency, when it can`() {
        val cases = listOf(
            "Total S$12.50" to "SGD",
            "Total £9.99" to "GBP",
            "Total RM 12.50" to "MYR",
            "Amount PHP 500.00" to "PHP",
            "Jumlah Rp 150.000" to "IDR",
            "Số tiền 50.000 VND" to "VND",
            "금액 12,000원" to "KRW",
            "合計 1,200円" to "JPY",
            "Razem 12,50 zł" to "PLN",
            "Celkem 250,00 Kč" to "CZK",
            "Total 24,90 €" to "EUR",
            "Toplam 120,00 TL" to "TRY",
        )
        for ((text, code) in cases) assertEquals(code, readSlip(text).currency, text)
    }

    @Test
    fun `a sign several currencies share is settled by the phone or not at all`() {
        assertEquals("SGD", readSlip("Total $12.50", SlipHints(homeCurrency = "SGD")).currency)
        assertEquals("USD", readSlip("Total $12.50", SlipHints(homeCurrency = "USD")).currency)
        // A phone counting in baht cannot say whose dollar this is, and
        // "dollars" in the form would be a guess dressed as a reading.
        val inBaht = readSlip("Total $12.50", SlipHints(homeCurrency = "THB"))
        assertEquals(12.5, inBaht.amount)
        assertEquals(null, inBaht.currency)
        assertTrue(inBaht.problems.contains("currency-ambiguous"), "problems were " + inBaht.problems)
        assertEquals("JPY", readSlip("合計 ¥1,200", SlipHints(homeCurrency = "JPY")).currency)
        assertEquals("CNY", readSlip("合計 ¥1,200", SlipHints(homeCurrency = "CNY")).currency)
        assertEquals(null, readSlip("合計 ¥1,200").currency)
    }

    @Test
    fun `a slip that converts is in the currency of the amount it picked`() {
        val r = readSlip("Amount USD 28.50\nTotal THB 1,000.00")
        assertEquals(1000.0, r.amount)
        assertEquals("THB", r.currency)
        // And with no label to choose by, two currencies are two answers.
        val bare = readSlip("1,000.00 THB 28.50 USD")
        assertEquals(null, bare.amount)
        assertEquals(null, bare.currency)
        assertTrue(bare.problems.containsAll(listOf("amount-ambiguous", "currency-ambiguous")))
    }

    @Test
    fun `the total outranks the amount, and fees, cash and change are none of them`() {
        assertEquals(105.0, readSlip("Amount 100.00\nFee 5.00\nTotal 105.00").amount)
        assertEquals(
            100.0,
            readSlip("Subtotal 120.00\nDiscount -20.00\nTotal 100.00\nCash 200.00\nChange 100.00").amount,
        )
        assertEquals(152500.0, readSlip("Jumlah Rp 150.000\nBiaya Admin Rp 2.500\nTotal Rp 152.500").amount)
    }

    @Test
    fun `tax inside a total does not make the total a tax`() {
        assertEquals(107.0, readSlip("Total (incl. VAT 7%) 107.00").amount)
        assertEquals(107.0, readSlip("ยอดรวมภาษี 107.00 บาท").amount)
    }

    @Test
    fun `a fee is a fee in every language`() {
        assertEquals(20.0, readSlip("Gebühr 1,50 €\nBetrag 20,00 €").amount)
        assertEquals(50000.0, readSlip("Phí giao dịch 1.100 VND\nSố tiền 50.000 VND").amount)
        assertEquals(500.0, readSlip("ค่าธรรมเนียมการชำระ 10.00 บาท\nจำนวนเงิน 500.00 บาท").amount)
        assertEquals(12.5, readSlip("Service fee S$1.00\nTotal paid S$12.50").amount)
    }

    @Test
    fun `labels survive the accents the recogniser drops`() {
        // What tha+eng made of "Số tiền" on a Vietnamese transfer.
        assertEquals(50000.0, readSlip("S6 tién\n50.000 VND\nPhi giao dich\n0 VND").amount)
    }

    @Test
    fun `a money sign the recogniser cannot read leaves the number standing`() {
        // ₱ is not in the English alphabet Tesseract ships with, and comes back
        // as P. The number after it is still the total; what it was in is
        // left to the page or the phone.
        assertEquals(500.0, readSlip("Total Amount Sent P500.00").amount)
        // Under a total label even a whole number behind one: Grab prints
        // รวมทั้งหมด ฿90, and the recogniser returns B90.
        assertEquals(90.0, readSlip("รวมทั้งหมด  B90").amount)
        // But not with nothing calling it the amount, and never behind an x,
        // which is how an account number is masked.
        assertEquals(null, readSlip("B52").amount)
        assertEquals(null, readSlip("Total x4410").amount)
    }

    @Test
    fun `other thai banks, written the way they print`() {
        // Hand-made from the layouts of two other banks: one prints no
        // currency at all, one prints everything in English.
        val noUnit = readSlip(
            "โอนเงินสำเร็จ\n08 ต.ค. 2569 - 18:10\nจำนวนเงิน\n1,250.00\nค่าธรรมเนียม\n0.00\n" +
                "รหัสอ้างอิง: 202610081810ABC12345",
        )
        assertEquals(1250.0, noUnit.amount)
        assertEquals("2026-10-08", noUnit.date)
        assertEquals("202610081810ABC12345", noUnit.reference)
        // Not assumed to be baht: the form fills in the phone's own currency
        // and says where it came from.
        assertEquals(null, noUnit.currency)
        assertEquals(listOf("no-currency"), noUnit.problems)

        val english = readSlip(
            "Transfer successful\n08 Oct 26, 18:10\nAmount\n1,250.00 THB\nFee\n0.00 THB\n" +
                "Transaction reference\n2026100818101234",
        )
        assertEquals(1250.0, english.amount)
        assertEquals("THB", english.currency)
        assertEquals("2026-10-08", english.date)
        assertEquals("2026100818101234", english.reference)
    }

    // ── what an independent review found ─────────────────────────────────────
    //
    // Every case below once came back as a confident wrong answer. Each is a
    // layout real slips use; the fix for each is named in SlipText.kt.

    @Test
    fun `a memo is what somebody typed, not a total`() {
        val kplus = listOf(
            "โอนเงินสำเร็จ", "8 ต.ค. 69 18:10 น.", "เลขที่รายการ:", "016281180000APM01111",
            "จำนวน:", "1,750.00 บาท", "ค่าธรรมเนียม:", "0.00 บาท", "สแกนตรวจสอบสลิป",
            "รายละเอียด: ค่าเช่ารวม 3,500",
        ).joinToString("\n")
        assertEquals(1750.0, readSlip(kplus).amount)
        assertEquals(500.0, readSlip("Amount 500.00 THB\nNote: split bill total 2,000").amount)
    }

    @Test
    fun `a number with rubbish on the end or a space in the middle is not read`() {
        for (text in listOf(
            "จำนวน:\n52.001 บาท\nค่าธรรมเนียม:\n0.00 บาท",
            "จำนวน:\n17 50 บาท\nค่าธรรมเนียม:\n0.00 บาท",
            "฿17 50",
            "Tisch 4 125,00 €",
        )) {
            val r = readSlip(text)
            assertEquals(null, r.amount, text)
            assertTrue(r.problems.contains("amount-unsure"), "$text: " + r.problems)
        }
    }

    @Test
    fun `a headline owns nothing, and a time is not money`() {
        // ชำระเงินสำเร็จ says "paid", and the line under it is the time, which
        // this bank writes with a dot.
        val bill = listOf(
            "ชำระเงินสำเร็จ", "8 ต.ค. 69 18.10 น.", "น.ส. สมศรี ใ", "เลขที่รายการ:",
            "016281180000APM01111", "จำนวน:", "52.00 บาท", "ค่าธรรมเนียม:", "0.00 บาท",
        ).joinToString("\n")
        assertEquals(52.0, readSlip(bill).amount)
        assertEquals(50000.0, readSlip("Thanh toán thành công\n18.10 08/10/2026\nSố tiền\n50.000 VND").amount)
    }

    @Test
    fun `labels in one column and values in another are not paired by guesswork`() {
        assertEquals(null, readSlip("Subtotal\nTax\nTotal\n\$20.00\n\$1.80\n\$21.80", SlipHints(homeCurrency = "USD")).amount)
        assertEquals(null, readSlip("จำนวนเงิน\nค่าธรรมเนียม\n1,250.00 บาท\n25.00 บาท").amount)
        // A caption under its value: the label above 5.00 belongs to 52.00.
        assertEquals(null, readSlip("฿52.00\nจำนวนเงิน\n฿5.00\nค่าธรรมเนียม").amount)
        // But a row whose value came back a line before its label is still a
        // row, and the total under it is still the total. This is what
        // tha+eng returned for a receipt printed Tip $3.00 / Total $22.92.
        val receipt = "Subtotal\n\$18.40\nTax\n\$1.52\n\$3.00\nTip\nTotal\n\$22.92\nVisa ****4242\n\$22.92"
        assertEquals(22.92, readSlip(receipt, SlipHints(homeCurrency = "USD")).amount)
    }

    @Test
    fun `thai words that contain a label are not that label`() {
        assertEquals(3000.0, readSlip("ยอดค้างชำระ 15,000.00 บาท\nจำนวนเงิน 3,000.00 บาท").amount, "outstanding")
        assertEquals(3000.0, readSlip("ยอดชำระขั้นต่ำ 1,500.00 บาท\nจำนวนเงิน 3,000.00 บาท").amount, "minimum")
        assertEquals(70.0, readSlip("ผัดผักรวม 60.00\nข้าวสวย 10.00\nจำนวนเงิน 70.00").amount, "a dish")
        assertEquals(107.0, readSlip("ราคาไม่รวม VAT 100.00\nVAT 7% 7.00\nจำนวนเงิน 107.00").amount, "excluding")
        assertEquals(87.5, readSlip("น้ำหนักสุทธิ 1.25 กก.\nรวม 87.50").amount, "net weight")
    }

    @Test
    fun `a table header owns no row, and a total that includes tax is still the total`() {
        assertEquals(null, readSlip("Description Qty Price Amount\nNasi Lemak 1 10.00 10.00\nTeh Tarik 1 3.50 3.50\nTOTA1 13.50").amount)
        assertEquals(90.0, readSlip("รายการ จำนวน ราคา\nข้าวผัด 1 50.00\nชาเย็น 2 40.00\nทั้งหมด 90.00").amount)
        assertEquals(13.78, readSlip("Nasi Lemak Set RM 13.00\nSST 6% RM 0.78\nTotal Incl. SST RM 13.78").amount)
        assertEquals(55500.0, readSlip("Item Rp 50.000\nTotal (termasuk PPN) Rp 55.500").amount)
        assertEquals(105.0, readSlip("Amount \$100.00\nFee \$5.00\nTotal (incl. fee) \$105.00", SlipHints(homeCurrency = "USD")).amount)
        assertEquals(150.0, readSlip("Total Saving ฿20.00\nAmount ฿150.00").amount)
        assertEquals(100.0, readSlip("Total Qty 2.00\nAmount 100.00").amount)
    }

    @Test
    fun `a discount with its label lost is still not the amount`() {
        assertEquals(null, readSlip("ค่าสินค้า/บริการ\n130 บาท\nสทธไทยชวยไทยพลส\n-78 บาท").amount)
    }

    @Test
    fun `a currency is the amount's own, not a word on the page or another line's`() {
        assertEquals("IDR", readSlip("TOKEN LISTRIK PLN\nPLN Prabayar\nTotal Bayar RP 20.000").currency)
        assertEquals(null, readSlip("WALMART\nTOTAL 12.50\nTRY OUR NEW APP", SlipHints(homeCurrency = "USD")).currency)
        assertEquals(null, readSlip("Total paid\n100.00\nRecipient gets\n3,550.00 THB").currency)
        assertEquals(null, readSlip("Total 1,000.00\nRate 1 USD = 35.50").currency)
    }

    @Test
    fun `an expiry date, a price and a code are not the day it was paid`() {
        assertEquals(null, readSlip("เติมเงินสำเร็จ\nว1สาดต=ออ9ร ววะบ.\nจำนวน:\n52.00 บาท\nคะแนนหมดอายุ 31/12/2569").date)
        assertEquals(null, readSlip("Voucher expires 31 Dec 2026\nTotal ฿100.00").date)
        assertEquals(null, readSlip("거래일시 26/10/08 18:10:25\n합계 11,000원").date, "not 2008")
        assertEquals(null, readSlip("1 SET 99.00\nTotal 99.00").date)
        assertEquals(null, readSlip("INV-10-12-25").date)
    }

    @Test
    fun `a Thai slip is one with Thai or baht on it, not one stray letter`() {
        assertEquals("2026-10-08", readSlip("Walmart\n10/08/2026 06:10 PM\nTOTAL 12.50\nแ", SlipHints(monthFirst = true)).date)
        assertEquals(
            "2026-08-10",
            readSlip("Transfer successful\n10/08/2026 18:10\nAmount 1,250.00 THB\nFee 0.00 THB", SlipHints(monthFirst = true)).date,
        )
    }

    // ── and what a second review found ────────────────────────────────────────
    //
    // Run against the rules above once they passed: wrong answers the new
    // rules still gave, and ordinary slips they had started refusing.

    @Test
    fun `a stray digit after the cents is not a thousand times the amount`() {
        // A dot is a decimal point on a Thai slip, so a dot and three digits
        // is a misreading, whatever is or is not left of the fee line.
        for (text in listOf("จํานวน:\n52.001 บาท\nค่าธรรมเนียม:\nฟรี", "จํานวนเงิน\n250.000")) {
            val r = readSlip(text)
            assertEquals(null, r.amount, text)
            assertTrue(r.problems.contains("amount-unsure"), "$text: " + r.problems)
        }
    }

    @Test
    fun `a lost point is still lost when the fee lost its point too`() {
        assertEquals(null, readSlip("จํานวน:\n1700 บาท\nค่าธรรมเนียม:\n000 บาท").amount)
        assertEquals(null, readSlip("จํานวน:\n1700 บาท\nค่าธรรมเนียม:\n0.0 บาท").amount)
        // And a fee read as 0,00 does not cast doubt on an amount of 1,250.00.
        assertEquals(1250.0, readSlip("จํานวน:\n1,250.00 บาท\nค่าธรรมเนียม:\n0,00 บาท").amount)
    }

    @Test
    fun `the time beside the date is not money, under any headline`() {
        val bill = "ทํารายการสําเร็จ\nชําระบิล\n08 ต.ค. 2569 - 18.10\nจํานวนเงิน\n1,284.50 บาท"
        assertEquals(1284.5, readSlip(bill).amount)
        // A Counter Service receipt: the bill, the fee, and what left.
        assertEquals(1294.5, readSlip("ยอดชําระ 1,284.50\nค่าธรรมเนียม 10.00\nรวมเงิน 1,294.50").amount)
    }

    @Test
    fun `a memo is a memo on the line under its label, and misspelled`() {
        assertEquals(1750.0, readSlip("จํานวน:\n1,750.00 บาท\nรายละเอียด:\nชําระหนี้งวดสุดท้าย 5,000").amount)
        assertEquals(1750.0, readSlip("จํานวน:\n1,750.00 บาท\nรายละเอยด: ชําระค่าเช่า ต.ค. 3,500").amount)
        assertEquals(500.0, readSlip("Amount 500.00 THB\nNote:\nsplit bill total 2,000").amount)
    }

    @Test
    fun `counts, litres, prices per litre and adverts are not money`() {
        assertEquals(241.0, readSlip("TOTAL  2 ITEMS  241.00").amount)
        assertEquals(241.0, readSlip("Total (2 items) 241.00").amount)
        assertEquals(1000.0, readSlip("ปริมาณ 28.180 L\nราคา/ลิตร 35.49\nรวมเงิน 1,000.00 บาท").amount)
        assertEquals(35.02, readSlip("PRICE/GAL \$3.459\nTOTAL \$35.02", SlipHints(homeCurrency = "USD")).amount)
        val advert = "โอนเงินสําเร็จ\n8 ต.ค. 69 18:10 น.\nจํานวน:\n52.00 บาท\nค่าธรรมเนียม:\n0.00 บาท\n" +
            "ยอดรวม 5,000 บาท ขึ้นไป ผ่อน 0%"
        assertEquals(52.0, readSlip(advert).amount)
        assertEquals(250.0, readSlip("รวมทั้งสิ้น 250.- บาท").amount, "the Thai way of writing a whole amount")
    }

    @Test
    fun `a label for an account, a card or a phone does not own the money`() {
        assertEquals(100.0, readSlip("Mobile Top-up\nP100.00").amount)
        assertEquals(1250.0, readSlip("Account Name\nJOHN DOE\nAmount\n1,250.00").amount)
        assertEquals(12.5, readSlip("CARD PAYMENT £12.50").amount)
        // And a debit printed as a negative is the amount when it is all
        // there is.
        assertEquals(12.5, readSlip("Card payment\n-£12.50\nOct 8, 2026").amount)
    }

    @Test
    fun `thousands grouped with spaces, with the label on the line above`() {
        assertEquals(1234.56, readSlip("MONTANT\n1 234,56 EUR").amount)
        assertEquals(12500.0, readSlip("Összesen\n12 500 Ft").amount)
        assertEquals(1250.0, readSlip("Totalt 1 250 kr", SlipHints(homeCurrency = "SEK")).amount)
    }

    @Test
    fun `a sign alone counts only where it can only be the unit`() {
        assertEquals(null, readSlip("จํานวนเงิน\n1,250.00\nRM").currency)
        assertEquals("THB", readSlip("Amount (THB)\n1,250.00").currency)
    }

    @Test
    fun `more ways a date is printed, and two that are not the date`() {
        for (text in listOf("08-Oct-2026", "08-OCT-26", "18:10-08/10/2026", "8 ต.ค. พ.ศ. 2569", "Datum 08.10.26")) {
            assertEquals("2026-10-08", readSlip(text).date, text)
        }
        assertEquals("2026-10-08", readSlip("วันที่ทํารายการ\n08 ต.ค. 2569 18:10\nวันที่มีผล 09 ต.ค. 2569").date)
        assertEquals(null, readSlip("Waktu 18.10.25 WIB").date, "the time to the second")
        assertEquals("2026-10-08", readSlip("08 Okt 2026\nWaktu 18.10.25 WIB").date)
    }

    @Test
    fun `a phone number or an ID card under a reference label is not copied`() {
        assertEquals(null, readSlip("Ref No.\n63 917 123 4567").reference)
        assertEquals(null, readSlip("UTR\n919876543210").reference)
        assertEquals(null, readSlip("รหัสอ้างอิง\n1-2345-67890-12-3").reference)
    }

    @Test
    fun `a word that is also a currency is one only beside a number`() {
        assertEquals(null, readSlip("You won!\nTotal 12.50").currency)
        assertEquals("THB", readSlip("TOP UP 100.00 THB").currency)
    }

    @Test
    fun `digits from other scripts and fullwidth forms are digits`() {
        assertEquals(500.0, readSlip("राशि ₹५००.००").amount)
        val r = readSlip("合計 ￥１，２００", SlipHints(homeCurrency = "JPY"))
        assertEquals(1200.0, r.amount)
        assertEquals("JPY", r.currency)
    }

    @Test
    fun `dates are read the way each country writes them`() {
        val cases = listOf(
            "Paid on 8 Oct 2026",
            "Oct 8, 2026 6:10 PM",
            "2026-10-08 18:10",
            "08.10.2026",
            "8 de octubre de 2026",
            "8. Oktober 2026",
            "8 Okt 2026 18:10 WIB",
            "2026年10月8日",
            "2026년 10월 8일",
            "ngày 8 tháng 10 năm 2026",
            "8 ตุลาคม 2569",
            "08/10/2569",
            "8 ต.ค. 69",
        )
        for (text in cases) assertEquals("2026-10-08", readSlip(text).date, text)
    }

    @Test
    fun `a date that reads both ways is settled by the phone, but never on a Thai slip`() {
        assertEquals("2026-08-10", readSlip("10/08/2026").date)
        assertEquals("2026-10-08", readSlip("10/08/2026", SlipHints(monthFirst = true)).date)
        // A Thai phone set to American English still gets Thai slips day first.
        assertEquals("2026-08-10", readSlip("โอนเงินสำเร็จ\n10/08/2026", SlipHints(monthFirst = true)).date)
        // And a date that can only be read one way is read that way.
        assertEquals("2026-10-25", readSlip("25/10/2026", SlipHints(monthFirst = true)).date)
    }

    @Test
    fun `a due date is not the day it was paid, and two days are a refusal`() {
        assertEquals("2026-10-08", readSlip("Date 08/10/2026\nDue date 15/10/2026").date)
        val two = readSlip("08/10/2026\n09/10/2026")
        assertEquals(null, two.date)
        assertTrue(two.problems.contains("date-ambiguous"), "problems were " + two.problems)
        assertEquals(null, readSlip("31/02/2026").date, "no calendar has it")
    }

    @Test
    fun `references from other countries`() {
        assertEquals("9012345678901", readSlip("Ref No. 9012 345 678901").reference, "GCash, spaced")
        assertEquals("9012345678901", readSlip("Ref No.\n9012 345 678901").reference, "the same, read as two lines")
        assertEquals("412345678901", readSlip("UPI Ref No: 412345678901").reference, "UPI, twelve digits")
        assertEquals("TX26100812345678", readSlip("Transaction ID\nTX26100812345678").reference)
        assertEquals("FT26281123456789", readSlip("Mã giao dịch: FT26281123456789").reference)
        assertEquals(
            "3f2a9c1e-0b4d-4e6f-9a8b-7c6d5e4f3a2b",
            readSlip("Reference 3f2a9c1e-0b4d-4e6f-9a8b-7c6d5e4f3a2b").reference,
        )
    }

    @Test
    fun `a number that is somebody's identity is not a reference`() {
        // Under a label whose own value is too short, or lost, the next number
        // on the page is a phone, a tax id, a card. None of them is copied.
        assertEquals(null, readSlip("Receipt No: 001234\nTel 081 234 5678").reference)
        assertEquals(null, readSlip("Receipt No.\nPOS 03\nTAX ID 0105556012345").reference)
        assertEquals(null, readSlip("เลขที่รายการ:\nเพียฟี\n081 234 5678").reference)
        assertEquals(null, readSlip("Reference\n4111-XXXX-XXXX-1111").reference)
        // A bill's Ref 1 is the customer's number at the biller — often a
        // national ID. The transaction's own number is the one wanted.
        assertEquals(
            "016281180000APM01111",
            readSlip("เลขที่อ้างอิง 1: 1234567890123\nเลขที่อ้างอิง 2: 0001\nเลขที่รายการ:\n016281180000APM01111").reference,
        )
        // And half a reference that wrapped onto the next line is not whole.
        assertEquals(null, readSlip("เลขที่รายการ:\n016281180000\nAPM01111").reference)
    }

    @Test
    fun `a reference that is cut or broken is not copied`() {
        val cut = readSlip("รหัสอ้างอิง 3f2a9c1e-0b4d-4e6f-9a8b-7c6d5e…")
        assertEquals(null, cut.reference)
        assertTrue(cut.problems.contains("reference-cut"), "problems were " + cut.problems)
        assertEquals(null, readSlip("Reference 016281180000 APM01111").reference)
    }

    // ─── what the payer typed ────────────────────────────────────────────────
    //
    // บันทึกช่วยจำ is the one thing on a slip the person chose. It becomes the
    // note, and its first word can name the category — see SlipInboxTest.

    @Test
    fun `the memo is what was typed after บันทึกช่วยจำ, beside the label or under it`() {
        assertEquals("ค่าข้าว", readSlip("จำนวน: 50.00 บาท\nบันทึกช่วยจำ: ค่าข้าว").memo)
        // Krungthai prints it right-aligned on the label's row, and the
        // recogniser hands the two halves back as two lines.
        assertEquals("จ่ายค่าเช่า", readSlip("จำนวนเงิน\n50.00 บาท\nบันทึกช่วยจำ\nจ่ายค่าเช่า").memo)
        assertEquals("rent october", readSlip("Amount 50.00 THB\nMemo: rent  october").memo)
        // Its numbers are still somebody's words and not the amount.
        val r = readSlip("จำนวนเงิน 50.00 บาท\nบันทึกช่วยจำ: ค่าข้าว 3 มื้อ 150 บาท")
        assertEquals(50.0, r.amount)
        assertEquals("ค่าข้าว 3 มื้อ 150 บาท", r.memo)
    }

    @Test
    fun `a memo with a label's word in it is still the whole memo`() {
        // บัตร is a card label and โทร a phone one. Beside a memo label they
        // used to be joined to it, and the memo was cut after them.
        assertEquals("จ่ายบัตร", readSlip("จำนวน: 50.00 บาท\nบันทึกช่วยจำ: จ่ายบัตร").memo)
        assertEquals("ค่าโทรศัพท์", readSlip("จำนวน: 50.00 บาท\nบันทึกช่วยจำ: ค่าโทรศัพท์").memo)
        assertEquals("ค่าบัตรคอนเสิร์ต 2 ใบ", readSlip("จำนวน: 50.00 บาท\nบันทึกช่วยจำ: ค่าบัตรคอนเสิร์ต 2 ใบ").memo)
        assertEquals(50.0, readSlip("จำนวน: 50.00 บาท\nบันทึกช่วยจำ: เติมเงินเบอร์ 0812345678").amount)
    }

    @Test
    fun `a heading the bank printed is not a memo`() {
        // K PLUS prints รายละเอียด with the wallet owner's name after it, and
        // LINE's card says ดูรายละเอียดที่ K PLUS. Nobody typed either.
        assertEquals(null, readSlip("รายละเอียด: สมศรี ใจงาม\nจำนวน: 52.00 บาท").memo)
        assertEquals(null, readSlip("จำนวนเงิน 98.00 บาท\nดูรายละเอียดที่ K PLUS").memo)
        assertEquals(null, readSlip("จำนวนเงิน 98.00 บาท\nบันทึกช่วยจำ:").memo, "an empty memo is no memo")
    }

    @Test
    fun `Krungthai's loopless letters still say memo and reference`() {
        val r = readSlip("รหัสฮ้างอิง Bd51b156c29d65f1e\nจำนวนเงิน\n1.00 บาท\nบันทึกชวยจำ\nจ่ายค่าน้ำ")
        assertEquals("Bd51b156c29d65f1e", r.reference)
        assertEquals("จ่ายค่าน้ำ", r.memo, "the whole label, not บันทึก and the rest of the word")
        assertEquals(1.0, r.amount)
    }

    @Test
    fun `a Krungthai reference in more letters than digits is read beside its label`() {
        // As the recogniser read a real one, made up in the same shape: both of
        // อ้างอิง's อ as ฮ, and the reference's 5s and 0 as letters, which left
        // four digits in seventeen.
        val r = readSlip("รหัสฮ้างฮิง BdhxOcT9e2ffa7cb1\nจำนวนเงิน\n1.00 บาท")
        assertEquals("BdhxOcT9e2ffa7cb1", r.reference)
        assertTrue("no-reference" !in r.problems, "problems were " + r.problems)
        // Under the label, where other things are printed, digits must still be
        // most of it.
        assertEquals(null, readSlip("รหัสอ้างอิง\nBdhxOcT9e2ffa7cb1\nจำนวนเงิน\n1.00 บาท").reference)
        assertEquals(null, readSlip("รหัสอ้างอิง Transferfromsavings").reference, "no digits at all")
    }

    // ─── Thai marks, as the recogniser hands them back ───────────────────────

    /** A string from code points, for marks in an order nobody would type. */
    private fun cp(vararg c: Int): String = c.joinToString("") { it.toChar().toString() }

    @Test
    fun `Thai marks come back in one order, once each`() {
        // น้ำ as น ้ ํ ้ า and as น ํ ้ า, both read off real fonts.
        val twice = cp(0x0E19, 0x0E49, 0x0E4D, 0x0E49, 0x0E32)
        val before = cp(0x0E19, 0x0E4D, 0x0E49, 0x0E32)
        val right = cp(0x0E19, 0x0E49, 0x0E33)
        assertEquals(right, thaiMarks(twice))
        assertEquals(right, thaiMarks(before))
        assertEquals(cp(0x0E17, 0x0E33), thaiMarks(cp(0x0E17, 0x0E4D, 0x0E32)), "ทํา is ทำ")
        // The tone typed ahead of the vowel: ป ้ ิ ง is ปิ้ง.
        assertEquals(cp(0x0E1B, 0x0E34, 0x0E49, 0x0E07), thaiMarks(cp(0x0E1B, 0x0E49, 0x0E34, 0x0E07)))
        assertEquals("ทดสอบ101 Shopee", thaiMarks("ทดสอบ101 Shopee"), "nothing to do")
        assertEquals("ค่า" + right + "ค่าไฟ", readSlip("จำนวนเงิน 50.00 บาท\nบันทึกช่วยจำ: ค่า" + twice + "ค่าไฟ").memo)
    }

    // ─── the memo, read a second time ────────────────────────────────────────

    @Test
    fun `the memo's words are found on the line the recogniser split them into`() {
        assertEquals(0, memoStart(listOf("เท", "ด", "ส", "อ", "บ", "101"), "เทดสอบ101"), "Krungthai: the whole line")
        assertEquals(1, memoStart(listOf("บันทึกช่วยจำ:", "อาหาร", "ทดสอบ"), "อาหารทดสอบ"), "K PLUS: after the label")
        assertEquals(1, memoStart(listOf("บันทึกช่วยจำ", ":อาหาร"), "อาหาร"), "the colon glued to the memo")
        assertEquals(2, memoStart(listOf("บันทึกช่วยจำ", ":", "อาหาร"), "อาหาร"), "a colon of its own is left out")
        assertEquals(-1, memoStart(listOf("จำนวนเงิน"), "อาหาร"))
        assertEquals(-1, memoStart(emptyList(), "อาหาร"))
    }

    @Test
    fun `the memo's line is cut a line's height wide, but not into a word before it`() {
        val memo = listOf(intArrayOf(300, 100, 360, 130), intArrayOf(362, 102, 400, 128))
        assertEquals(listOf(270, 91, 430, 139), memoCut(memo, null, null, 1000, 1000).toList(), "the line to itself")
        assertEquals(272, memoCut(memo, intArrayOf(272, 100, 287, 130), ":", 1000, 1000)[0], "a colon in front is let in whole")
        assertEquals(290, memoCut(memo, intArrayOf(250, 100, 290, 130), "จำ", 1000, 1000)[0], "a word in front is not")
        assertEquals(0, memoCut(listOf(intArrayOf(10, 100, 60, 130)), null, null, 1000, 1000)[0], "not past the edge")
    }

    @Test
    fun `a line read on its own may still have the label on it`() {
        assertEquals("อาหารทดสอบ", memoOnLine("บันทึกช่วยจำ: อาหารทดสอบ"))
        assertEquals("อาหารทดสอบ", memoOnLine(": อาหารทดสอบ"))
        // จำ: at the edge of the cut, as K PLUS's came back.
        assertEquals("ค่าข้าว", memoOnLine("]: ค่าข้าว"))
        assertEquals("ซื้อของ", memoOnLine("1: ซื้อของ"))
        assertEquals("ค่าข้าว 3 มื้อ: 150", memoOnLine("ค่าข้าว 3 มื้อ: 150"), "a colon further in is the memo's own")
        assertEquals("ทดสอบ101", memoOnLine("ทดสอบ101\n"))
        assertEquals(null, memoOnLine("บันทึกช่วยจำ:"))
    }

    @Test
    fun `the memo's line read again wins unless it is clearly the worse reading`() {
        // The real Krungthai slip: the page saw เทดสอบ101, the line on its own ทดสอบ101.
        assertEquals("ทดสอบ101", betterMemo(MemoRead("เทดสอบ101", 89.1f), MemoRead("ทดสอบ101", 92.0f)))
        // Nothing read again, or nothing in it: the page's.
        assertEquals("เทดสอบ101", betterMemo(MemoRead("เทดสอบ101", 89.1f), null))
        assertEquals("เทดสอบ101", betterMemo(MemoRead("เทดสอบ101", 89.1f), MemoRead("", 0f)))
        // The label caught in the cut is taken off again.
        assertEquals("อาหารทดสอบ", betterMemo(MemoRead("อาหารกดสอบ", 92.8f), MemoRead("บันทึกช่วยจำ: อาหารทดสอบ", 87.8f)))
        // A third longer or shorter: something else got into the cut.
        assertEquals("ค่าข้าว", betterMemo(MemoRead("ค่าข้าว", 90f), MemoRead("ค่าข้าว 0.00 บาท ค่าธรรมเนียม", 95f)))
        // The page much surer of its own.
        assertEquals("กาแฟ", betterMemo(MemoRead("กาแฟ", 93.8f), MemoRead("ภาาแฟ", 80.9f)))
        // Two readings that differ only in the order of their marks are one.
        val twice = cp(0x0E19, 0x0E49, 0x0E4D, 0x0E49, 0x0E32)
        assertEquals("ค่า" + cp(0x0E19, 0x0E49, 0x0E33), betterMemo(MemoRead("ค่า$twice", 85f), MemoRead("ค่าน้ำ", 90f)))
    }

    // ─── the time of day ─────────────────────────────────────────────────────

    @Test
    fun `times of day on a slip, and numbers that only look like one`() {
        assertEquals(listOf(20 * 60 + 38), clocksOn("11 ต.ค. 69 20:38 น."))
        assertEquals(listOf(20 * 60 + 38), clocksOn("Ti ต.ค. 69 20.38น"), "a dot only right before น")
        assertEquals(listOf(20 * 60 + 39), clocksOn("11 a.A. 2569 - 20:39"))
        assertEquals(listOf(9 * 60 + 30), clocksOn("เวลา 09.30"))
        assertEquals(listOf(20 * 60 + 5), clocksOn("Oct 11, 2026 8:05 PM"))
        assertEquals(listOf(15), clocksOn("12:15 am"))
        assertEquals(emptyList(), clocksOn("จำนวนเงิน 20.38 บาท"), "twenty baht and thirty-eight satang")
        assertEquals(emptyList(), clocksOn("1,020.38"))
        assertEquals(emptyList(), clocksOn("25:10"))
    }
}
