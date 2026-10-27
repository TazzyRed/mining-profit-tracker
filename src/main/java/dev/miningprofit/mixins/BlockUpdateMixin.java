/*
 * Taken from SkyblockCollectionTracker (ChindeaOne), LGPL v2.1; see LICENSE.
 */
package dev.miningprofit.mixins;

import dev.miningprofit.BlockHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Server block changes, before they're applied (the old block is still there: what broke). Both handlers
// bounce off the network thread first, so HEAD runs twice - the network-thread call is skipped.
@Mixin(ClientPacketListener.class)
public class BlockUpdateMixin {

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void mp$beforeBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !mc.isSameThread()) return;
        BlockHooks.onBlockChange(packet.getPos(), level.getBlockState(packet.getPos()), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void mp$beforeChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !mc.isSameThread()) return;
        packet.runUpdates((pos, state) -> BlockHooks.onBlockChange(pos, level.getBlockState(pos), state));
    }
}
