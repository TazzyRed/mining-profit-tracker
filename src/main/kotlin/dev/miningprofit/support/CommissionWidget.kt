package dev.miningprofit.support

/** The commissions you have, from the tab list's "Commissions:" lines ("Mithril Miner: 50%", "Goblin Slayer: DONE"). */
object CommissionWidget {

    class Commission(val key: String, val progress: String) {
        val completed: Boolean get() = progress.equals("DONE", ignoreCase = true)
    }

    private val LINE = Regex("""^(.+?):\s*(DONE|\d+(?:\.\d+)?%)$""", RegexOption.IGNORE_CASE)

    var commissions: List<Commission> = emptyList()
        private set

    fun onTabUpdate() {
        val island = IslandTracker.currentMiningIsland
        if (island != "Dwarven Mines" && island != "Crystal Hollows" && island != "Mineshaft" && island != "Glacite Tunnels") {
            commissions = emptyList()
            return
        }
        val parsed = Tab.commissionLines.mapNotNull { line ->
            LINE.find(line.trim())?.let { Commission(it.groupValues[1].trim().lowercase(), it.groupValues[2]) }
        }
        if (parsed.isNotEmpty()) commissions = parsed
    }
}
