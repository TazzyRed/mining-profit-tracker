package dev.miningprofit.gui

import dev.miningprofit.profit.ProfitTracker
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents

/**
 * With chat or a container open (your inventory too), Commission Stats and the profit tracker are drawn over it (not
 * dimmed under its background), and left-clicking one of their lines hides it (struck through while the screen's open, gone
 * without one) or shows it again. The click goes no further when it hits a line.
 */
object LineClicks {
    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (!RatesOverlay.onTop(screen)) return@register
            ScreenEvents.afterExtract(screen).register { _, g, _, _, _ ->
                RatesOverlay.render(g, top = true)
                ProfitTracker.render(g, top = true)
            }
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                if (event.button() != 0) return@register true
                val mx = event.x
                val my = event.y
                !(RatesOverlay.click(mx, my) || ProfitTracker.click(mx, my))
            }
        }
    }
}
