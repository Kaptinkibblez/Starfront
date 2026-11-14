package com.starwake.mixin;

import com.starwake.ship.yard.Shipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shipyard hooks (M9). Ship blocks live in real chunks of the {@code starwake:shipyard} dimension, so:
 * every block change there must reach its ship (the ship's snapshot drives physics, rendering and clients),
 * and entities spawned there (drops, XP) belong at the ship's real position instead. {@code
 * updatePOIOnBlockStateChange} is the one call every effective {@code setBlock} makes on the server.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelShipyardMixin {
	// HEAD, not TAIL: a TAIL injection here never fired in 26.3.
	@Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
	private void starwake$shipyardChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		if (oldState != newState) Shipyard.onBlockChanged((ServerLevel) (Object) this, pos, oldState, newState);
	}

	/** Sounds made aboard (doors, chests, machines) are heard where the ship really is. */
	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
			at = @At("HEAD"), cancellable = true)
	private void starwake$shipyardSound(Entity except, double x, double y, double z, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
			net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
		ServerLevel self = (ServerLevel) (Object) this;
		if (!Shipyard.isShipyard(self)) return;
		ci.cancel();
		var at = Shipyard.toWorld(x, y, z);
		if (at != null) at.ship().level().playSeededSound(null, at.pos().x, at.pos().y, at.pos().z, sound, source, volume, pitch, seed);
	}

	/** Level events aboard (door/trapdoor sounds, break particles) play at the ship's real position. */
	@Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
	private void starwake$shipyardLevelEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
		ServerLevel self = (ServerLevel) (Object) this;
		if (!Shipyard.isShipyard(self)) return;
		ci.cancel();
		var at = Shipyard.toWorld(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (at != null) at.ship().level().levelEvent(null, type, BlockPos.containing(at.pos()), data);
	}

	/** Sign edits and other block-entity updates without a state change reach the ship's snapshot too. */
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void starwake$shipyardData(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
		if (old == current) Shipyard.onBlockDataChanged((ServerLevel) (Object) this, pos);
	}

	/** Block events aboard (chest lids) are replayed by clients tracking the ship. */
	@Inject(method = "doBlockEvent", at = @At("RETURN"))
	private void starwake$shipyardBlockEvent(net.minecraft.world.level.BlockEventData data, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) Shipyard.onBlockEvent((ServerLevel) (Object) this, data.pos(), data.paramA(), data.paramB());
	}

	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void starwake$relocateFromShipyard(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (Shipyard.relocate((ServerLevel) (Object) this, entity)) cir.setReturnValue(true);
	}
}
