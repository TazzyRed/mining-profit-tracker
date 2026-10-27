package dev.miningprofit.support

/** Which island you're on, from the tab list's "Area:" line. */
object IslandTracker {

    private val miningAreas = setOf(
        "Dwarven Mines", "Crystal Hollows", "Mineshaft", "Gold Mine", "Deep Caverns", "Glacite Tunnels",
        "Hub", "The End", "Crimson Isle", "Spider's Den", "The Farming Islands", "Jerry's Workshop",
    )

    var currentMiningIsland: String? = null
        private set

    fun onTabUpdate() {
        currentMiningIsland = Tab.area?.takeIf { it in miningAreas }
    }

    fun reset() {
        currentMiningIsland = null
    }
}
