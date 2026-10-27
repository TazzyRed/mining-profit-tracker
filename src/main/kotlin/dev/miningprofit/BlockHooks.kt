package dev.miningprofit

import dev.miningprofit.profit.ProfitTracker
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/** Blocks broken, for the profit tracker: the server's block updates and your client's own breaks (from the mixins). */
object BlockHooks {

    /** A block change the server sent (main thread, before it's applied). */
    @JvmStatic
    fun onBlockChange(pos: BlockPos, old: BlockState, new: BlockState) {
        if (old.block != new.block && (new.isAir || new.`is`(Blocks.BEDROCK))) ProfitTracker.onBlockGone(pos, old, byYou = false)
    }

    /** Your client broke a block itself (before it's removed). */
    @JvmStatic
    fun onClientDestroy(pos: BlockPos, state: BlockState) {
        ProfitTracker.onBlockGone(pos, state, byYou = true)
    }
}
