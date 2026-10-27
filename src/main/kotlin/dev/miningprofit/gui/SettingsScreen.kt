package dev.miningprofit.gui

import dev.miningprofit.support.Config
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** /miningprofit: the settings. Click a row to switch it (or step it on); the last row opens Move GUIs. */
class SettingsScreen(private val parent: Screen?) : Screen(Component.literal("Mining Profit Tracker")) {

    private class Row(val label: String, val tip: String, val value: () -> String, val on: () -> Boolean?, val click: () -> Unit)

    private fun toggle(label: String, tip: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Row(label, tip, { if (get()) "ON" else "OFF" }, { get() }) { set(!get()) }

    private fun cycle(label: String, tip: String, names: List<String>, get: () -> Int, set: (Int) -> Unit) =
        Row(label, tip, { names.getOrElse(get()) { "?" } }, { null }) { set((get() + 1) % names.size) }

    private val rows = listOf(
        toggle("Profit tracker", "The profit overlay: drops, pets and loot, per hour.", { Config.profitTracker }) { Config.profitTracker = it },
        toggle("Commission Stats", "Comms/hr, and in the Glacite Tunnels Glacite Pickos/hr and Shafts/hr.", { Config.ratesOverlay }) { Config.ratesOverlay = it },
        toggle("Sell offer prices", "Price at sell offer instead of instant sell.", { Config.profitSellOffer }) { Config.profitSellOffer = it },
        toggle("Exact numbers", "9,184,222/hr instead of 9.2m/hr.", { Config.profitExact }) { Config.profitExact = it },
        cycle("Gemstones as", "What gemstones are priced as.", listOf("Flawed", "Fine", "Flawless"), { Config.profitGemTier }) { Config.profitGemTier = it },
        cycle("Metals as", "Glacite, umber, tungsten, mithril, titanium: enchanted, or as they drop.", listOf("Enchanted", "As dropped"),
            { Config.profitMetalTier }) { Config.profitMetalTier = it },
        cycle("Pet prices", "Lowest BIN now, or what the pet sold for on average over the last week.", listOf("Lowest BIN", "1-week avg"),
            { if (Config.petPriceWeekAvg) 1 else 0 }) { Config.petPriceWeekAvg = it == 1 },
        toggle("Dragon pets in two tiers", "Rose, Golden and Jade Dragons: XP under 100 valued to 100, from 100 on to 200.",
            { Config.profitDragonTiers }) { Config.profitDragonTiers = it },
        toggle("Reset sessions each launch", "Every overlay session starts afresh when the game starts.",
            { Config.overlayResetOnLaunch }) { Config.overlayResetOnLaunch = it },
        cycle("Core of the Mountain", "Level 2 or more: Maniac Miner lasts longer (for its fortune).", listOf("1", "2+"),
            { if (Config.cotmLevel >= 2) 1 else 0 }) { Config.cotmLevel = if (it == 1) 10 else 1 },
        Row("Move GUIs", "Drag the overlays where you want them.", { "" }, { null }) { OverlayMover.start(this) },
    )

    private val rowW = 260
    private val rowH = 18
    private val gap = 3
    private fun left() = (width - rowW) / 2
    private fun top() = maxOf(30, (height - rows.size * (rowH + gap)) / 2)
    private fun rowAt(mx: Int, my: Int): Int? {
        if (mx < left() || mx > left() + rowW || my < top()) return null
        val i = (my - top()) / (rowH + gap)
        return i.takeIf { it in rows.indices && (my - top()) % (rowH + gap) < rowH }
    }

    override fun isPauseScreen() = false

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), Theme.BACKDROP)
        val x = left()
        val title = Component.literal("Mining Profit Tracker").withStyle(net.minecraft.ChatFormatting.BOLD)
        g.text(font, title, x + (rowW - font.width(title)) / 2, top() - 18, Theme.ACCENT, true)
        val hover = rowAt(mouseX, mouseY)
        for ((i, r) in rows.withIndex()) {
            val y = top() + i * (rowH + gap)
            Theme.panel(g, x, y, rowW, rowH, if (i == hover) Theme.ROW_HOVER else Theme.ROW, if (i == hover) Theme.ACCENT else Theme.BORDER)
            g.text(font, r.label, x + 6, y + 5, Theme.TEXT, false)
            val v = r.value()
            if (v.isNotEmpty()) {
                val w = font.width(v) + 10
                val on = r.on()
                Theme.box(g, x + rowW - w - 4, y + 3, w, rowH - 6, if (on == true) Theme.ON_BG else Theme.OFF_BG)
                g.text(font, v, x + rowW - w + 1, y + 5, if (on == true) Theme.ON_TEXT else Theme.OFF_TEXT, false)
            }
        }
        hover?.let {
            val tip = rows[it].tip
            g.text(font, tip, left() + (rowW - font.width(tip)) / 2, top() + rows.size * (rowH + gap) + 6, Theme.TEXT_DIM, false)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val i = rowAt(event.x.toInt(), event.y.toInt()) ?: return super.mouseClicked(event, doubled)
        rows[i].click()
        Config.save()
        return true
    }

    override fun onClose() {
        Config.save()
        Minecraft.getInstance().setScreen(parent)
    }

    companion object {
        /** Opened on the next tick, so chat has closed first. */
        fun open() {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreen(SettingsScreen(null)) }
        }
    }
}
