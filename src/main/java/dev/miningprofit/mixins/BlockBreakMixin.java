/*
 * Taken from SkyblockCollectionTracker (ChindeaOne), LGPL v2.1; see LICENSE.
 */
package dev.miningprofit.mixins;

import dev.miningprofit.BlockHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A block your client breaks itself (block-break prediction: the server's update for it won't show it anymore).
@Mixin(MultiPlayerGameMode.class)
public class BlockBreakMixin {

    @Unique
    private BlockState mp$destroying;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void mp$beforeDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ClientLevel level = Minecraft.getInstance().level;
        mp$destroying = level == null ? null : level.getBlockState(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void mp$afterDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = mp$destroying;
        mp$destroying = null;
        if (state != null && Boolean.TRUE.equals(cir.getReturnValue())) BlockHooks.onClientDestroy(pos, state);
    }
}
