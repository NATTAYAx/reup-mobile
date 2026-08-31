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





    // ── the second real slip ─────────────────────────────────────────────────


    //


    // Same bank, same app, one week later, and it moved the two things that


    // were still open. The amount came back this time — the recogniser is in


    // sparse mode now, and the largest line on the page finally reaches the


    // text. And the reference moved: four lines of noise between the label and


    // the number instead of one, which the old three-line window could not


    // reach.


    //


    // Copied character for character off the phone, including every piece of


    // rubbish. That is the point of keeping these: a slip invented by hand


    // never has "ie. 7" in the middle of it.





    private val secondRealSlip = listOf(


        "เติมเงินสำเร็จ",


        "<X+",


        "30 ส.ค. 69 15:35 น.",


        "น.ส. ณัฐธยาน์ แ",


        "ธ.กสิกรไทย",


        "%%%-%-%8762-%",


        "ง/",


        "GrabPay Wallet",


        "197558477",


        "เลขที่รายการ:",


        "7 !",


        "ie. 7",


        "oe",


        "016242153507527223",


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


        assertEquals("016242153507527223", r.reference)


        assertEquals(emptyList(), r.problems)


    }





    @Test


    fun `the fee is still not the amount when it is three lines below its label`() {


        // The fee label carries no digits of its own and 0.00 arrives further


        // down than last time. If the exclusion missed it, this would read a


        // two baht top-up as a zero baht one, or refuse to read it at all.


        val changed = secondRealSlip.replace("0.00 บาท", "35.00 บาท")


        assertEquals(2.0, readSlip(changed).amount)


    }





    @Test


    fun `the account number is not mistaken for the reference`() {


        // 197558477 is a wallet id and sits five lines above the label. A rule


        // that took the longest digit run on the page would take it on some


        // other slip, which is why this one only looks below a label.


        val r = readSlip(secondRealSlip)


        assertEquals("016242153507527223", r.reference)


    }



    // ── the third real slip, where the recogniser lost the reference ─────────

    //

    // Same bank, same app, next day. Amount, currency and date came back right.

    // The reference number 016243133947BPM06163 did not come back at all — it

    // was returned as Thai syllables, because at that size the shapes fit Thai

    // better than digits.

    //

    // Kept because it is the case the rules must NOT solve. There is nothing

    // left of the number to find, and any rule that produced one would be

    // producing it out of noise.

    //

    // Transcribed from two screenshots, so a line or two in the middle of the

    // rubbish may be missing. Nothing asserted below depends on that stretch.



    private val thirdRealSlip = listOf(

        "เติมเงินสำเร็จ",

        "31 ส.ค. 69 13:39 น.",

        "<X+",

        "น.ส. ณัฐธยาน์ แ",

        "ธ.กสิกรไทย",

        "6",

        "%%%-%-%8762-%",

        "ง/",

        "[Prompt] Pay",

        "พร้อมเพย์ อี-วอลเล็ต / G-Wallet iil",

        "006-xXxxxxXxxx-3650",

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

        "รายละเอียด: ณัฐธยาน์ แย้มหลั่งทรัพย์",

        ).joinToString("\n")



    @Test

    fun `the third real slip reads the money and admits the reference is gone`() {

        val r = readSlip(thirdRealSlip)

        assertEquals(70.6, r.amount)

        assertEquals("THB", r.currency)

        assertEquals("2026-08-31", r.date)

        assertEquals(null, r.reference)

        // The point of the whole test. A blank field and "problems none" side

        // by side is the screen saying nothing is wrong while something is.

        assertEquals(listOf("no-reference"), r.problems)

    }



    @Test

    fun `the wallet number above the label is not taken as the reference`() {

        // 006-xXxxxxXxxx-3650 is the only long thing near the label and it sits

        // above it. A rule that widened its search upwards, or gave up and took

        // the longest run on the page, would file a wallet number as a

        // reference and nothing downstream would ever notice.

        assertEquals(null, readSlip(thirdRealSlip).reference)

    }



    @Test

    fun `an unlabelled amount still wins when the fee lost its currency word`() {

        // On this slip จำนวน: was lost and its 70.60 came back with a bare

        // colon, while ค่าธรรมเนียม kept its label and its 0.00 came back as

        // "0.00 as". Neither number is labelled, and the reading still has to

        // be the transfer rather than a refusal.

        assertEquals(70.6, readSlip(thirdRealSlip).amount)

    }

    // ── the fourth real slip, where a bare digit almost became the amount ────
    //
    // Same slip as the third, read after the image was enlarged before
    // recognition. Everything got worse: the date that had read correctly came
    // back as ว1สาดต=ออ9ร, the 70.60 came back as จ9, and the reference arrived
    // in two halves with a space in the middle.
    //
    // The reason it is kept is not the recogniser. It is that the rules read
    // `จ9` as nine baht under a จำนวน label, said nothing was wrong, and
    // offered to file it. This text is here so that can never happen twice.

    private val fourthRealSlip = listOf(
        "เติมเงินสำเร็จ",
        "ว1สาดต=ออ9ร ววะบ.",
        "<+",
        "น.ส. ณัฐธยาน์ แ",
        "ธ.กสิกรไทย",
        "XXX-X-x8/62-xX",
        ">              ๑        4% <         4",
        "1",
        "พร้อมเพย์ อี-วอลเล็ต / G-Wallet",
        "Prompt}",
        "Pay",
        "006-%%%%%%%-3650",
        "เลขที่รายการ:",
        "ยย",
        "01624313394 7BRMOGI63",
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
        "รายละเอียด: ณัฐธยาน์ แย้มหลั่งทรัพย์",
    ).joinToString("\n")

    @Test
    fun `a bare digit under an amount label is not an amount`() {
        // The whole point. Nine is what the recogniser made of 70.60, and a
        // wrong number here becomes a row in the ledger nobody rechecks.
        val r = readSlip(fourthRealSlip)
        assertEquals(null, r.amount)
        assertTrue(r.problems.contains("no-amount"), "problems were " + r.problems)
    }

    @Test
    fun `half a reference number is refused rather than shortened`() {
        // 01624313394 is the first eleven characters of a twenty-character
        // reference, and it is indistinguishable from a real one once it has
        // been written into a note.
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
        // not null too. Worth pinning: it means currency is the weakest of the
        // four, not the strongest.
        assertEquals("THB", readSlip(fourthRealSlip).currency)
    }
}