package com.starwake.client.mixin;

import com.starwake.client.ship.ShipBlockEntities;
import com.starwake.ship.yard.Shipyard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sign editing aboard ships (M9 pass 2): the server opens the editor for the sign's shipyard position, which this
 * client has no block entity for. Edit a copy of the ship's sign at that position instead; the screen then sends
 * the new text for that position and the server applies it to the real sign.
 */
@Mixin(ClientPacketListener.class)
abstract class SignEditorMixin {
	@Inject(method = "handleOpenSignEditor", at = @At("HEAD"), cancellable = true)
	private void starwake$signAboard(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!Shipyard.inYard(packet.pos()) || minecraft.level == null || minecraft.player == null) return;
		if (!minecraft.isSameThread()) return; // vanilla re-schedules the packet onto the main thread first
		// The server opens the editor while placing the sign, before the ship's updated snapshot (with the new sign)
		// reaches us: wait for it rather than drop the request.
		ci.cancel();
		ShipBlockEntities.openSignEditor(packet.pos(), packet.slot());
	}
}
