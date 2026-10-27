package dev.miningprofit.support

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.PlayerInfo

/**
 * The tab list, read every few ticks and cut into its sections ("Area:", "Commissions:").
 */
object Tab {

    private val order = compareBy<PlayerInfo>({ it.team?.name ?: "" }, { it.profile.name })
    private var cache: List<String> = emptyList()
    private var level: ClientLevel? = null
    private var ticks = 0L

    /** The lines under a heading, colour codes stripped. */
    var area: String? = null
        private set
    var commissionLines: List<String> = emptyList()
        private set
    /** Every line, colour codes stripped (the profit tracker reads your fortune off them). */
    var lines: List<String> = emptyList()
        private set
    /** Every line as it is, colour codes and all. */
    var rawLines: List<String> = emptyList()
        private set
    /** The colour (RGB) each line's last bit of text is written in (a pet's rarity is its name's colour); null: none. */
    var colors: List<Int?> = emptyList()
        private set
    private var pendingColors: List<Int?> = emptyList()

    fun onClientTick(client: Minecraft) {
        if (ticks++ % 4L != 0L) return
        val world = client.level
        if (world == null) {
            level = null
            return
        }
        if (world !== level) {
            level = world
            area = null
            commissionLines = emptyList()
            IslandTracker.reset()
        }
        val lines = read(client) ?: return
        if (lines.isEmpty() || lines == cache) return
        cache = lines
        this.lines = lines.map { it.strip() }
        rawLines = lines
        colors = pendingColors
        area = section(lines, Regex("(?:§.)*(?:Area|Dungeon): (?:§.)*(?<island>.*)"))
            ?.let { (head, _) -> Regex("(?:Area|Dungeon): (.*)").find(head.strip())?.groupValues?.get(1)?.trim() }
        commissionLines = section(lines, Regex("(?:§.)*Commissions:"))?.second ?: emptyList()
        IslandTracker.onTabUpdate()
        CommissionWidget.onTabUpdate()
        // The pet's XP bar: every change of it is read
        dev.miningprofit.profit.PetTracker.read()
    }

    // A heading and the indented lines under it (at most ten, as Hypixel writes them)
    private fun section(lines: List<String>, heading: Regex): Pair<String, List<String>>? {
        val start = lines.indexOfFirst { heading.matches(it) }
        if (start < 0) return null
        val body = ArrayList<String>(10)
        for (i in start + 1 until lines.size) {
            if (body.size >= 10) break
            val line = lines[i]
            if (!line.removePrefix("§r").startsWith(" ")) break
            val plain = line.strip()
            if (plain.isBlank()) continue
            body += plain
        }
        return lines[start].strip() to body
    }

    private fun read(client: Minecraft): List<String>? {
        val player = client.player ?: return null
        val tab = client.gui.tabList
        val names = player.connection.onlinePlayers.sortedWith(order).map { tab.getNameForDisplay(it) }
        pendingColors = names.map { lastColor(it) }
        // Every entry: Hypixel's tab list runs past the 80 of old (its later columns hold your stats - fortune)
        return names.map { it.string }
    }

    // The colour the last of [c]'s text is written in
    private fun lastColor(c: net.minecraft.network.chat.Component): Int? {
        var found: Int? = null
        c.visit({ style: net.minecraft.network.chat.Style, part: String ->
            if (part.isNotBlank()) found = style.color?.value?.and(0xFFFFFF)
            java.util.Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
        return found
    }

    /** Colour codes off, trimmed. */
    private fun String.strip() = replace(COLOR, "").trim()
    private val COLOR = Regex("§.")
}
