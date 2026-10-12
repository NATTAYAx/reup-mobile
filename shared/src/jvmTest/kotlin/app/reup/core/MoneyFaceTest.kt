package app.reup.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

// ─── MoneyFaceTest ───────────────────────────────────────────────────────────
//
// Mostly about printing numbers, which sounds too small to test until a dollar
// row reads "$180" next to "$12.50" and nobody can tell whether the first one
// lost its cents.

class MoneyFaceTest {

    @Test
    fun `baht drops the zeros and keeps real satang`() {
        assertEquals("฿4,230", moneyText(4230.0, "THB"))
        assertEquals("฿60.50", moneyText(60.5, "THB"))
        assertEquals("฿1,240", moneyText(1240.0, "thb"))
        assertEquals("฿0", moneyText(0.0, "THB"))
    }

    @Test
    fun `a dollar always shows its cents`() {
        assertEquals("+$180.00", moneyText(180.0, "USD", sign = 1))
        assertEquals("$12.50", moneyText(12.5, "USD"))
    }

    @Test
    fun `the sign is a real minus, so a column of them lines up`() {
        assertEquals("−฿60", moneyText(60.0, "THB", sign = -1))
        // The amount is stored positive; the direction comes from the row.
        assertEquals("−฿60", moneyText(-60.0, "THB", sign = -1))
    }

    @Test
    fun `thousands are grouped at every size`() {
        assertEquals("฿999", moneyText(999.0, "THB"))
        assertEquals("฿1,000", moneyText(1000.0, "THB"))
        assertEquals("฿1,234,567", moneyText(1234567.0, "THB"))
    }

    @Test
    fun `floating point does not leak into the cents`() {
        assertEquals("฿60", moneyText(59.999999, "THB"))
        assertEquals("$0.30", moneyText(0.1 + 0.2, "USD"))
    }

    @Test
    fun `a unit with no cents rounds, and one with no symbol says its code`() {
        assertEquals("¥1,241", moneyText(1240.6, "JPY"))
        assertEquals("−1,240 MYR", moneyText(1240.0, "MYR", sign = -1))
        assertEquals("12.50 MYR", moneyText(12.5, "MYR"))
    }

    @Test
    fun `a value that is not a number is not printed as one`() {
        assertEquals("—", moneyText(Double.NaN, "THB"))
    }

    @Test
    fun `seeded categories read in Thai, named ones as named`() {
        assertEquals("อาหาร", expenseLabel("food", null))
        assertEquals("ค่าบิล", expenseLabel("bills", ""))
        assertEquals("กาแฟเช้า", expenseLabel("food", "กาแฟเช้า"))
        assertEquals("pets", expenseLabel("pets", null))
        assertEquals("อื่นๆ", expenseLabel(null, null))
        assertEquals(true, isBuiltInExpense("game"))
        assertEquals(false, isBuiltInExpense("pets"))
        assertEquals(false, isBuiltInExpense(null))
    }

    @Test
    fun `what was typed is the title and the drawer is the detail`() {
        assertEquals(MoneyLine("กาแฟ", "อาหาร"), moneyLine(false, "food", "กาแฟ", "อาหาร"))
        assertEquals(MoneyLine("อาหาร", null), moneyLine(false, "food", "  ", "อาหาร"))
        assertEquals(MoneyLine("TELUS", "รายรับ"), moneyLine(true, "TELUS", null, ""))
        assertEquals(MoneyLine("TELUS", "รายรับ · ค่าแปล"), moneyLine(true, "TELUS", "ค่าแปล", ""))
        assertEquals(MoneyLine("ค่าแปล", "รายรับ"), moneyLine(true, "", "ค่าแปล", ""))
        assertEquals(MoneyLine("รายรับ", null), moneyLine(true, null, null, ""))
    }

    @Test
    fun `a day of money is named, and dated beside the name`() {
        val today = LocalDate(2026, 10, 8)
        assertEquals("วันนี้", moneyDayTitle("2026-10-08", today))
        assertEquals("เมื่อวาน · พุธ 7 ต.ค.", moneyDayTitle("2026-10-07", today))
        assertEquals("จันทร์ 5 ต.ค.", moneyDayTitle("2026-10-05", today))
        assertEquals("ศุกร์ 26 ธ.ค. 2025", moneyDayTitle("2025-12-26", today))
        // A timestamp is read by its date.
        assertEquals("วันนี้", moneyDayTitle("2026-10-08T09:30:00", today))
        // Not a date at all: printed as found rather than dropped.
        assertEquals("เมื่อไรก็ไม่รู้", moneyDayTitle("เมื่อไรก็ไม่รู้", today))
    }

    @Test
    fun `the month above the title`() {
        assertEquals("ตุลาคม", monthName(10))
        assertEquals("มกราคม", monthName(1))
        assertEquals("", monthName(13))
    }

    @Test
    fun `rows left out of the totals are counted, and nothing is said when there are none`() {
        assertEquals("อีก 1 รายการเป็นสกุลเงินอื่น ไม่ได้รวมในยอดนี้", otherUnitsLine(1))
        assertEquals(null, otherUnitsLine(0))
    }
}
