package dev.miningprofit.gui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The look: dark panels, a warm yellow accent, green for what's switched on. A "rounded" box is a fill with its four
 * corner pixels taken off.
 */
object Theme {

    const val BACKDROP = 0xD00A0B0C.toInt()
    const val PANEL = 0xFF23262B.toInt()
    const val BORDER = 0xFF474C54.toInt()
    const val ROW = 0xFF2B2F35.toInt()
    const val ROW_HOVER = 0xFF343943.toInt()

    const val TEXT = 0xFFDFE3E8.toInt()
    const val TEXT_DIM = 0xFF9AA1A9.toInt()
    const val ACCENT = 0xFFF0C23C.toInt()

    const val ON_BG = 0xFF3F6B3C.toInt()
    const val ON_TEXT = 0xFFCDF0C6.toInt()
    const val OFF_BG = 0xFF3A3E45.toInt()
    const val OFF_TEXT = 0xFFA6ADB5.toInt()

    /** A filled box with its corners taken off. */
    fun box(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x + 1, y, x + w - 1, y + h, color)
        g.fill(x, y + 1, x + 1, y + h - 1, color)
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }

    /** The same, outlined. */
    fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, fill: Int, border: Int) {
        box(g, x, y, w, h, border)
        box(g, x + 1, y + 1, w - 2, h - 2, fill)
    }
}
