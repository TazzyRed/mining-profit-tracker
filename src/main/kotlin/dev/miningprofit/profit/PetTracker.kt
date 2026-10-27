package dev.miningprofit.profit

import dev.miningprofit.support.Tab

/**
 * The tab list's Pet widget ("[Lvl 85] Scatha", then "1,234/5,678 XP (21%)" or "MAX LEVEL"; its rarity is
 * its name's colour): the XP bar itself, read every time the tab list changes (Tab calls [read]) and passed
 * on to [ExpShare], which takes each reading as that pet's XP. Also the XP each level takes: Hypixel's pet
 * table (from the NotEnoughUpdates repo).
 */
object PetTracker {

    // XP for each level, from the rarity's offset (level L to L+1 is LEVELS[offset + L - 1])
    private val LEVELS = longArrayOf(100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440, 490, 540, 600, 660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500, 21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200, 137200, 146700, 156700, 167700, 179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700, 441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700, 1746700, 1886700)
    // A pet's rarity is the colour its name is written in: the rarity and where its levels start in the table
    private val BY_COLOR = mapOf(0xFFFFFF to ("COMMON" to 0), 0x55FF55 to ("UNCOMMON" to 6), 0x5555FF to ("RARE" to 11),
        0xAA00AA to ("EPIC" to 16), 0xFFAA00 to ("LEGENDARY" to 20), 0xFF55FF to ("MYTHIC" to 20))
    // Golden / Jade / Rose Dragon go on to 200, each level past 100 this much
    private const val DRAGON_LEVEL = 1_886_700L

    var name: String? = null
        private set
    var level = 0
        private set
    var maxed = false
        private set

    private val LVL = Regex("""\[Lvl (\d+)]\s*(.+)""")
    private val XP = Regex("""([\d,.]+[kKmMbB]?)\s*/\s*([\d,.]+[kKmMbB]?) XP""")
    private val PERCENT = Regex("""\((\d+(?:\.\d+)?)%\)""")

    private fun num(s: String): Double {
        val t = s.replace(",", "")
        val mult = when (t.last().lowercaseChar()) { 'k' -> 1e3; 'm' -> 1e6; 'b' -> 1e9; else -> 1.0 }
        return (if (mult == 1.0) t else t.dropLast(1)).toDoubleOrNull()?.times(mult) ?: 0.0
    }

    /** XP level [l] takes to the next, for a rarity's [offset] into the table. */
    fun need(l: Int, offset: Int): Long = if (l >= 100) DRAGON_LEVEL else LEVELS.getOrElse(offset + l - 1) { DRAGON_LEVEL }
    /** XP from level 1 to level [l]. */
    fun upTo(l: Int, offset: Int): Double { var s = 0.0; for (k in 1 until l) s += need(k, offset); return s }

    /** Read from the tab list, whenever it changes. */
    fun read() {
        if (!dev.miningprofit.support.Config.profitTracker) return
        val lines = Tab.lines
        val head = lines.indexOfFirst { it == "Pet:" }
        if (head < 0 || head + 1 >= lines.size) return
        val m = LVL.find(lines[head + 1]) ?: run {
            if (lines[head + 1].contains("No pet", ignoreCase = true)) {
                name = null
                ExpShare.observeNoPet()
            }
            return
        }
        val pet = m.groupValues[2].trim()
        level = m.groupValues[1].toInt()
        // Its rarity: the colour its name's written in (not told: null, and what's known of it stands)
        val rated = Tab.colors.getOrNull(head + 1)?.let { BY_COLOR[it] }
        val offset = rated?.second ?: 20
        val rarity = rated?.first
        // Golden, Jade and Rose Dragons go on to 200
        val maxLevel = if (pet.contains("Dragon") && (pet.startsWith("Golden") || pet.startsWith("Jade") || pet.startsWith("Rose"))) 200 else 100
        name = pet
        val progress = lines.getOrNull(head + 2) ?: ""
        maxed = "MAX LEVEL" in progress || level >= maxLevel
        val x = XP.find(progress)
        // The XP into the level: as it's written, or - when it's shortened ("1.2M") - by the percentage
        val into = when {
            x == null -> 0.0
            x.groupValues[1].last().isLetter() -> PERCENT.find(progress)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it / 100.0 * need(level, offset) }
                ?: num(x.groupValues[1])
            else -> num(x.groupValues[1])
        }
        ExpShare.observeTab(pet, offset, rarity, level, upTo(level, offset) + into)
    }
}
