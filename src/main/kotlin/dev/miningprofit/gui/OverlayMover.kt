package dev.miningprofit.gui

import dev.miningprofit.profit.ProfitTracker
import dev.miningprofit.support.Config
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Move GUIs (/miningprofit gui): Commission Stats and the profit tracker. Press on one, drag it, let go to drop
 * it there; right-click a line to hide it (or show it again: hidden ones are struck through); Tab shows the profit
 * tracker of the next place (each place has its own, all in the same spot). Escape when you're done.
 */
class OverlayMover(private val parent: Screen?) : Screen(Component.literal("Move GUIs")) {

    // [prefix]: what its hidden lines are saved under ("comm", or the profit tracker's place)
    private class Box(val name: () -> String, val on: () -> Boolean, val rows: () -> List<Pair<String?, String>>,
                      val prefix: () -> String, var x: Int, var y: Int, val save: (Int, Int) -> Unit)

    private val boxes = listOf(
        Box({ "Commission Stats" }, { Config.ratesOverlay },
            { RatesOverlay.previewRows.map { it.id to it.text } }, { RatesOverlay.KEY }, Config.overlayX, Config.overlayY) { x, y ->
            Config.overlayX = x
            Config.overlayY = y
        },
        Box({ "Profit tracker: " + (ProfitTracker.NAMES[ProfitTracker.previewKey] ?: "") + "  §7(Tab: next place)" }, { Config.profitTracker },
            { ProfitTracker.rows.map { it.id to it.text } }, { ProfitTracker.previewKey ?: "" }, Config.profitX, Config.profitY) { x, y ->
            Config.profitX = x
            Config.profitY = y
        },
    )

    private var held: Box? = null
    private var grabX = 0
    private var grabY = 0

    companion object {
        /** Opens it on the next tick, so the settings screen (or chat) has closed first. */
        fun start(parent: Screen?) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreen(OverlayMover(parent)) }
        }
    }

    override fun init() {
        super.init()
        // Where you are's first
        if (ProfitTracker.previewKey == null) ProfitTracker.previewKey = ProfitTracker.placeKey() ?: ProfitTracker.HOLLOWS
    }

    override fun isPauseScreen() = false

    // A box's lines as drawn: the hidden ones dim and struck through
    private fun lines(b: Box): List<String> {
        val prefix = b.prefix()
        return b.rows().map { (id, text) ->
            if (id != null && "$prefix.$id" in Config.hiddenLines) RatesOverlay.struck(text) else text
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        RatesOverlay.build(force = true)
        ProfitTracker.build(force = true)
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x900A0B0C.toInt())

        for (b in boxes) {
            val lines = lines(b)
            val w = RatesOverlay.width(lines)
            val h = RatesOverlay.height(lines)
            if (b === held) {
                b.x = (mouseX - grabX).coerceIn(0, maxOf(0, g.guiWidth() - w))
                b.y = (mouseY - grabY).coerceIn(0, maxOf(0, g.guiHeight() - h))
            }
            val over = held == null && inside(b, mouseX, mouseY)
            Theme.panel(g, b.x - 3, b.y - 3, w + 6, h + 6, 0x00000000, if (b === held || over) Theme.ACCENT else Theme.BORDER)
            RatesOverlay.draw(g, lines, b.x, b.y)
            // Which it is, under it (over it at the bottom of the screen)
            if (b === held || over) {
                val label = b.name() + if (b.on()) "" else " §c(off)"
                val ly = if (b.y + h + 14 > g.guiHeight()) b.y - 14 else b.y + h + 5
                g.text(font, label, b.x, ly, Theme.ACCENT, true)
            }
        }

        val hint = "Drag to move  ·  Right-click a line to hide or show it  ·  Tab: next place's profit tracker  ·  Esc when done"
        g.text(font, hint, (g.guiWidth() - font.width(hint)) / 2, g.guiHeight() - 24, Theme.TEXT_DIM, false)
    }

    private fun inside(b: Box, mx: Int, my: Int): Boolean {
        val lines = lines(b)
        return mx >= b.x - 3 && mx <= b.x + RatesOverlay.width(lines) + 3 && my >= b.y - 3 && my <= b.y + RatesOverlay.height(lines) + 3
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = event.x.toInt()
        val my = event.y.toInt()
        // The one drawn last is on top
        val b = boxes.lastOrNull { inside(it, mx, my) } ?: return super.mouseClicked(event, doubled)
        if (event.button() == 1) {
            val row = ((my - b.y) / font.lineHeight).coerceAtLeast(0)
            val id = b.rows().getOrNull(row)?.first ?: return true
            val k = "${b.prefix()}.$id"
            if (!Config.hiddenLines.remove(k)) Config.hiddenLines.add(k)
            Config.save()
            return true
        }
        held = b
        grabX = mx - b.x
        grabY = my - b.y
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val b = held ?: return super.mouseReleased(event)
        held = null
        b.save(b.x, b.y)
        Config.save()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // Tab: the next place's profit tracker
        if (event.key() == 258) {
            val keys = ProfitTracker.KEYS
            ProfitTracker.previewKey = keys[(keys.indexOf(ProfitTracker.previewKey) + 1).mod(keys.size)]
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        held?.let { it.save(it.x, it.y) }
        Config.save()
        ProfitTracker.previewKey = null
        minecraft?.setScreen(parent)
    }

    override fun removed() {
        ProfitTracker.previewKey = null
        super.removed()
    }
}
