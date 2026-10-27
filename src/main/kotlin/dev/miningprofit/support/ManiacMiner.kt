package dev.miningprofit.support

import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

/**
 * Whether Maniac Miner is on (the profit tracker adds its fortune per block while it is): from "You used your Maniac
 * Miner Pickaxe Ability!" for its duration - by your Core of the Mountain level ([Config.cotmLevel]), one level more
 * with a Blue Cheese Goblin Omelette part in the drill it was used with.
 */
object ManiacMiner {

    private val USED = Regex("""^You used your Maniac Miner Pickaxe Ability!""")
    private var endsAt = 0L

    val on: Boolean get() = System.currentTimeMillis() < endsAt

    fun onChat(text: String) {
        if (!USED.containsMatchIn(text)) return
        val level = (if (Config.cotmLevel >= 2) 2 else 1) + if (blueCheese(Minecraft.getInstance().player?.mainHandItem)) 1 else 0
        val seconds = when (level) { 1 -> 25; 2 -> 30; else -> 35 }
        endsAt = System.currentTimeMillis() + seconds * 1000L
    }

    /** A drill with a Blue Cheese Goblin Omelette part, by its tooltip. */
    fun blueCheese(stack: ItemStack?): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        if (stack == null || stack.isEmpty) return false
        return try {
            val context = net.minecraft.world.item.Item.TooltipContext.of(player.level().registryAccess())
            stack.getTooltipLines(context, player, net.minecraft.world.item.TooltipFlag.NORMAL)
                .any { "blue cheese goblin omelette part" in it.string.replace(Regex("§."), "").lowercase() }
        } catch (_: Exception) {
            false
        }
    }
}
