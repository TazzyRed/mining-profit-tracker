package dev.miningprofit.commissions

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

/**
 * Glacite Pickos/hr: a Pickobulus thrown in the Glacite Tunnels, where it lands worked out the
 * way the pickaxe flies, counted when its blast has more glacite than any other ore worth having. The aim is the one at the right-click (the chat message comes a moment later).
 */
object GlacitePickos {

    private const val CLICK_MATCH_MS = 1500L
    private var clickAt = 0L
    private var clickEye: Vec3? = null
    private var clickLook: Vec3? = null

    /** You right-clicked (with anything): where you were looking, for a Pickobulus message that may follow. */
    fun onUse() {
        val player = Minecraft.getInstance().player ?: return
        clickAt = System.currentTimeMillis()
        clickEye = player.eyePosition
        clickLook = player.lookAngle
    }

    /** When your last Pickobulus was used, and where it lands (null: it couldn't be worked out) - for the profit tracker. */
    var usedAt = 0L
        private set
    var landing: net.minecraft.core.BlockPos? = null
        private set

    fun onChat(text: String) {
        if (!text.startsWith("You used your Pickobulus")) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val recent = System.currentTimeMillis() - clickAt <= CLICK_MATCH_MS
        val eye = (if (recent) clickEye else null) ?: player.eyePosition
        val look = (if (recent) clickLook else null) ?: player.lookAngle
        clickEye = null
        clickLook = null
        val impact = PickoRules.throwTarget(level, eye, look)
        usedAt = System.currentTimeMillis()
        landing = impact
        if (impact == null) return
        val profile = PickoRules.current() ?: return
        if (profile !== PickoRules.GLACITE) return
        val counts = PickoRules.simulate(level, impact, profile)
        val glacite = counts["glacite"]
        if (glacite > 0 && profile.valuable.all { it == "glacite" || counts[it] < glacite }) CommissionsTracker.registerGlacitePicko()
    }
}
