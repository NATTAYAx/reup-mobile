package app.reup.core

// ─── Power.kt - the battery layer a brand adds on top of Android ─────────────
//
// Android has one switch this app can read: whether it is exempt from the
// standard battery optimisation. Several brands put a second manager on top
// that the app cannot see at all, and on those phones the first switch can be
// green while reminders are still being stopped. This is the honest version of
// that: it names the brand the phone actually is, says what to look for, and
// admits the app cannot check it.
//
// It replaced a banner that said Samsung on every phone, written when the only
// phone was a Samsung.
//
// Search words rather than menu paths, because paths move between versions of
// each brand's software, and a path that is wrong is worse than a word that
// finds the right page. dontkillmyapp.com keeps the current paths for every
// brand, which is why each entry points there instead of pretending to.

data class BrandPower(
    /** As the brand writes its own name. */
    val brand: String,
    /** What to switch, as words to type into the settings search. */
    val steps: List<String>,
    /** Where the current menu paths are kept. */
    val guide: String,
)

private val DISPLAY = mapOf(
    "iqoo" to "iQOO",
    "vivo" to "vivo",
    "samsung" to "Samsung",
    "xiaomi" to "Xiaomi",
    "redmi" to "Redmi",
    "poco" to "POCO",
    "oppo" to "OPPO",
    "realme" to "realme",
    "oneplus" to "OnePlus",
    "huawei" to "Huawei",
    "honor" to "HONOR",
)

/**
 * The extra layer on this phone, or null when the brand has none worth naming.
 *
 * Takes both Build.MANUFACTURER and Build.BRAND because they disagree on the
 * phones that matter most: an iQOO reports vivo as its manufacturer and iQOO
 * as its brand. The steps follow the manufacturer, whose software it is; the
 * name follows the brand, which is what is printed on the phone.
 */
fun brandPowerFor(manufacturer: String, brand: String): BrandPower? {
    val m = manufacturer.trim().lowercase()
    val b = brand.trim().lowercase()
    fun has(vararg names: String) = names.any { it == m || it == b }
    val shown = DISPLAY[b] ?: DISPLAY[m] ?: brand.trim().ifEmpty { manufacturer.trim() }
    val guide = "dontkillmyapp.com/" + (if (m in DISPLAY) m else b)
    val steps = when {
        has("vivo", "iqoo") -> listOf(
            "พื้นหลัง → ให้ Reup ใช้พลังงานพื้นหลังสูง",
            "Autostart → เปิด Reup",
            "ล็อก Reup ไว้ในหน้าแอปล่าสุด",
        )
        has("samsung") -> listOf(
            "Never sleeping apps → เพิ่ม Reup",
            "Put unused apps to sleep → ปิด",
        )
        has("xiaomi", "redmi", "poco") -> listOf(
            "Autostart → เปิด Reup",
            "Battery saver ของ Reup → No restrictions",
        )
        has("oppo", "realme", "oneplus") -> listOf(
            "Auto launch → เปิด Reup",
            "แบตเตอรี่ของ Reup → อนุญาตกิจกรรมเบื้องหลัง",
        )
        has("huawei", "honor") -> listOf(
            "App launch → Reup → จัดการเอง แล้วเปิดทั้งสามสวิตช์",
        )
        else -> return null
    }
    return BrandPower(shown, steps, guide)
}