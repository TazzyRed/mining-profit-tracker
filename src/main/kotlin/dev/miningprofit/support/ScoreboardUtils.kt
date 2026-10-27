package dev.miningprofit.support

import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/** The scoreboard sidebar: its lines, and where you are (the line carrying Hypixel's location symbol). */
object ScoreboardUtils {

    private val symbols = Regex("""[]\s*""")
    private val color = Regex("§.")
    private var ticks = 0L

    var location: String = ""
        private set

    /** Every sidebar line, colour codes stripped. */
    var lines: List<String> = emptyList()
        private set

    fun onClientTick(client: Minecraft) {
        if (ticks++ % 4L != 0L) return
        val world = client.level ?: return
        val scoreboard = world.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.BY_ID.apply(1))
        if (objective == null) {
            location = ""
            this.lines = emptyList()
            return
        }
        val lines = scoreboard.listPlayerScores(objective)
            .sortedByDescending { it.value }
            .mapNotNull { score ->
                val team = scoreboard.getPlayersTeam(score.ownerName().string) ?: return@mapNotNull null
                (team.playerPrefix.string + team.playerSuffix.string).replace(color, "").trim().ifEmpty { null }
            }
        this.lines = lines
        val here = lines.firstNotNullOfOrNull { line ->
            val s = line.trimStart()
            if (symbols.containsMatchIn(s)) s else null
        } ?: return
        location = here.replace(symbols, "").trim()
    }
}
