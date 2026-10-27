package dev.miningprofit.gui

import dev.miningprofit.commissions.CommissionsTracker
import dev.miningprofit.support.CommissionWidget
import dev.miningprofit.support.Config
import dev.miningprofit.support.IslandTracker
import dev.miningprofit.support.StringUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Commission Stats: Comms/hr (hovered: the session), and in the Glacite Tunnels Glacite Pickos/hr and Shafts/hr, over
 * the uptime commission progress keeps running ([CommissionsTracker]), and that uptime (Session). Lines
 * "comm.rate", "comm.time", "comm.pickos" and "comm.shafts" can be hidden ([Config.hiddenLines]). Also the drawing both overlays share.
 *
 * Based on SkyblockCollectionTracker's commissions overlay (ChindeaOne, LGPL v2.1; see LICENSE).
 */
object RatesOverlay {

    private const val TITLE = "§b§lCommission Stats"
    const val KEY = "comm"

    /** A line: [id] names it for hiding (none: always shown). */
    class Row(val id: String?, val text: String)

    var rows: List<Row> = emptyList()
        private set
    private var lines: List<String> = emptyList()
    private var shownRows: List<Row> = emptyList()
    private var rateRow = -1
    private var builtAt = 0L
    private var builtOpen = false

    /** What it shows, or a stand-in when it's empty (for the screen that moves it). */
    val previewRows: List<Row> get() = rows.ifEmpty {
        listOf(Row(null, TITLE), Row("rate", "§d✦ §7Comms/hr: §e0.00"), Row("pickos", "§9✦ §7Glacite Pickos/hr: §b0.0"),
            Row("shafts", "§5✦ §7Shafts/hr: §d0.00"), Row("time", "§3✦ §7Session: §b00:00"))
    }

    fun hidden(id: String) = "$KEY.$id" in Config.hiddenLines

    /** A hidden line as drawn while it can be clicked back on: dim and struck through. */
    fun struck(text: String) = "§8§m" + text.replace(Regex("§."), "")

    /** Chat or a container (your inventory too): the overlays are drawn over it, and their lines can be clicked to hide them. */
    fun onTop(s: net.minecraft.client.gui.screens.Screen?) =
        s is net.minecraft.client.gui.screens.ChatScreen || s is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>

    /** One of those ([onTop]) is open. */
    fun screenOpen(): Boolean = onTop(Minecraft.getInstance().screen)

    /** A click at [mx], [my] with a screen open: on a line that can be hidden, hides it (or shows it again). Whether it was. */
    fun click(mx: Double, my: Double): Boolean {
        if (lines.isEmpty()) return false
        val font = Minecraft.getInstance().font
        val x = Config.overlayX
        val y = Config.overlayY
        if (my < y || mx < x || mx > x + width(lines)) return false
        val id = shownRows.getOrNull(((my - y) / font.lineHeight).toInt())?.id ?: return false
        val k = "$KEY.$id"
        if (!Config.hiddenLines.remove(k)) Config.hiddenLines.add(k)
        Config.save()
        build(force = true)
        return true
    }

    /** [top]: drawn over the open screen (see [onTop]); the HUD's own drawing leaves it to that then. */
    fun render(g: GuiGraphicsExtractor, top: Boolean = false) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.debugOverlay.showDebugScreen()) return
        if (!top && screenOpen()) return
        // Being moved: that screen draws it
        if (mc.screen is OverlayMover) return
        build()
        if (lines.isEmpty()) return
        draw(g, lines, Config.overlayX, Config.overlayY)
        hoverDetail(g, mc, Config.overlayX, Config.overlayY)
    }

    // With the mouse free (chat open, say) and over the Comms/hr line: the session in more detail, beside the overlay
    private fun hoverDetail(g: GuiGraphicsExtractor, mc: Minecraft, x: Int, y: Int) {
        if (mc.screen == null) return
        val row = rateRow.takeIf { it in lines.indices } ?: return
        val font = mc.font
        val window = mc.window
        val mx = mc.mouseHandler.getScaledXPos(window)
        val my = mc.mouseHandler.getScaledYPos(window)
        val top = y + row * font.lineHeight
        if (mx < x || mx > x + font.width(lines[row]) || my < top || my >= top + font.lineHeight) return
        val done = CommissionsTracker.getCompletedCount()
        val active = CommissionsTracker.getActiveDurationMs()
        val each = if (done > 0) active / 1000.0 / done else 0.0
        val detail = arrayListOf(
            "§b§lSession",
            "§7Completed: §f$done",
            "§7Uptime: §f" + CommissionsTracker.getUptime() + if (CommissionsTracker.isPausedForInactivity()) " §c(paused)" else "",
            "§7Per commission: §f" + String.format("%.0f", each) + "s",
            "§7Timer paused: §f" + CommissionsTracker.pauseCount + "×§7 (§f" +
                StringUtils.formatTime(CommissionsTracker.getPausedMs() / 1000L) + "§7 idle)",
        )
        if (CommissionsTracker.region == CommissionsTracker.GLACITE) {
            detail.add("§7Mineshafts entered: §f" + CommissionsTracker.shaftsEntered)
            detail.add("§7Glacite Pickobulus: §f" + CommissionsTracker.glacitePickos +
                " §7(" + String.format("%.2f", CommissionsTracker.getGlacitePickosPerHour()) + "/hr)")
        }
        val w = detail.maxOf { font.width(it) } + 8
        val h = detail.size * font.lineHeight + 6
        val bx = x + width(lines) + 8
        g.fill(bx, top - 3, bx + w, top - 3 + h, Theme.PANEL)
        g.fill(bx, top - 3, bx + w, top - 2, Theme.ACCENT)
        var ly = top
        for (l in detail) {
            g.text(font, l, bx + 4, ly, 0xFFFFFFFF.toInt(), true)
            ly += font.lineHeight
        }
    }

    /** Draws [lines] at [x], [y]; used by the overlays themselves and by the screen that moves them. */
    fun draw(g: GuiGraphicsExtractor, lines: List<String>, x: Int, y: Int) {
        val font = Minecraft.getInstance().font
        var ly = y
        for (line in lines) {
            if (line.isNotEmpty()) g.text(font, line, x, ly, 0xFFFFFFFF.toInt(), true)
            ly += font.lineHeight
        }
    }

    fun width(lines: List<String>): Int {
        val font = Minecraft.getInstance().font
        return lines.maxOfOrNull { font.width(it) } ?: 0
    }

    fun height(lines: List<String>): Int = lines.size * Minecraft.getInstance().font.lineHeight

    /** Rebuilt a few times a second: the tab list only moves that often anyway. */
    fun build(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val open = screenOpen()
        if (!force && now - builtAt < 250L && open == builtOpen) return
        builtAt = now
        builtOpen = open
        rows = make()
        // With a screen open the hidden ones are there too, struck through, to click back on
        val shown = if (open) rows else rows.filter { it.id == null || !hidden(it.id) }
        shownRows = shown
        rateRow = shown.indexOfFirst { it.id == "rate" && !hidden(it.id) }
        lines = shown.map { if (it.id != null && hidden(it.id)) struck(it.text) else it.text }
    }

    // Since when the Dwarven Mines' tab list has shown no commissions (0: it has them)
    private var missingSince = 0L
    private const val MISSING_MS = 5_000L

    private fun make(): List<Row> {
        val island = IslandTracker.currentMiningIsland
        // In the Dwarven Mines (and the Glacite Tunnels, which the tab list calls that) with a tab list but no
        // "Commissions:" in it: the widget's turned off
        val now = System.currentTimeMillis()
        if (Config.ratesOverlay && island == "Dwarven Mines" && dev.miningprofit.support.Tab.lines.isNotEmpty() &&
            dev.miningprofit.support.Tab.commissionLines.isEmpty()) {
            if (missingSince == 0L) missingSince = now
            if (now - missingSince >= MISSING_MS) return listOf(
                Row(null, TITLE),
                Row(null, "§c⚠ Can't read your commissions from the tab list."),
                Row(null, "§7Turn on: §fSettings §7→ §fTablist Widgets §7→ §fDwarven Mines"),
                Row(null, "§7→ §fCommissions Widget"),
            )
        } else missingSince = 0L
        // Only where there are commissions to have, not the Hollows', and from the session's first commission progress
        if (!Config.ratesOverlay || CommissionWidget.commissions.isEmpty() || island == "Crystal Hollows" || !CommissionsTracker.running) return emptyList()
        val out = ArrayList<Row>()
        out.add(Row(null, TITLE))
        val paused = if (CommissionsTracker.isPausedForInactivity()) " §c§l(PAUSED)" else ""
        out.add(Row("rate", "§d✦ §7Comms/hr: §e" + String.format("%.2f", CommissionsTracker.getCommissionsPerHour())))
        if (CommissionsTracker.region == CommissionsTracker.GLACITE) {
            out.add(Row("pickos", "§9✦ §7Glacite Pickos/hr: §b" + String.format("%.1f", CommissionsTracker.getGlacitePickosPerHour())))
            out.add(Row("shafts", "§5✦ §7Shafts/hr: §d" + String.format("%.2f", CommissionsTracker.shaftsPerHour())))
        }
        // The session's time, at the bottom as the profit tracker has it
        out.add(Row("time", "§3✦ §7Session: §b" + CommissionsTracker.getUptime() + paused))
        return out
    }
}
