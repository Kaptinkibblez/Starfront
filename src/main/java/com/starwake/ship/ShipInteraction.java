package com.starwake.ship;

import com.starwake.network.ShipInteractPayload;
import com.starwake.network.ShipStructurePayload;
import com.starwake.ship.block.FuelTankBlock;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.data.ShipFuels;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;

/**
 * Server side of interacting with an assembled ship: syncing its blocks to clients, and
 * handling right-clicks on ship blocks (found client-side by a ship-space raycast).
 */
public final class ShipInteraction {
	private ShipInteraction() {}

	public static void init() {
		// M9: clients get the ship physics tuning on join and after every reload (for flight prediction).
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ShipFooting.forget(handler.player));
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				sender.sendPacket(new com.starwake.network.ShipPhysicsPayload(com.starwake.ship.data.ShipPhysics.get())));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			for (var player : server.getPlayerList().getPlayers()) {
				ServerPlayNetworking.send(player, new com.starwake.network.ShipPhysicsPayload(com.starwake.ship.data.ShipPhysics.get()));
			}
		});
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof ShipEntity ship && ship.structure() != null) {
				ServerPlayNetworking.send(player, new ShipStructurePayload(ship.getId(), ship.structureVersion(), ship.structure()));
				ServerPlayNetworking.send(player, com.starwake.network.ShipDynamicsPayload.of(ship.getId(), ship.dynamics()));
			}
		});
	}

	/** Fuel items and propellant gas cylinders top up the ship's shared tanks (M3/M8f). */
	private static boolean isFuel(ItemStack stack) {
		var gas = stack.get(com.starwake.registry.ModComponents.GAS);
		return ShipFuels.unitsFor(stack) > 0 || gas != null && com.starwake.ship.fuel.Propellants.SPECIES.contains(gas.species());
	}

	/**
	 * M9: everything else is vanilla, done for real in the ship's shipyard plot: the block is used first (doors,
	 * chests, machines, levers: their menus stay open because reach is measured on the flying ship), unless the
	 * player sneaks with something in hand; then the held item is used on it. Block items build onto the ship
	 * with ship-local orientation ({@link com.starwake.ship.edit.ShipEditing#place}).
	 */
	private static void useInPlot(ServerPlayer player, ShipEntity ship, ShipInteractPayload payload) {
		ServerLevel yard = com.starwake.ship.yard.Shipyard.level(player.level().getServer());
		if (yard == null || ship.plot() < 0) return;
		BlockPos plotPos = com.starwake.ship.yard.Shipyard.toPlot(ship.plot(), payload.local());
		Vec3 hit = com.starwake.ship.yard.Shipyard.toPlot(ship.plot(), payload.hit());
		BlockHitResult hitResult = new BlockHitResult(hit, payload.face(), plotPos, false);
		BlockState state = yard.getBlockState(plotPos);
		boolean handsFull = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
		boolean suppressUse = player.isSecondaryUseActive() && handsFull;
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (!suppressUse) {
				InteractionResult result = state.useItemOn(stack, yard, player, hand, hitResult);
				if (result.consumesAction()) {
					return;
				}
				if (result instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) {
					result = state.useWithoutItem(yard, player, hitResult);
					if (result.consumesAction()) {
						return;
					}
				}
			}
			if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
				if (com.starwake.ship.edit.ShipEditing.place(player, ship, hand, payload.local(), payload.face(), payload.hit())) return;
			} else if (!stack.isEmpty()) {
				InteractionResult result = stack.useOn(new net.minecraft.world.item.context.UseOnContext(yard, player, hand, stack, hitResult) {});
				if (result.consumesAction()) {
					return;
				}
			}
		}
	}

	public static void handle(ServerPlayer player, ShipInteractPayload payload) {
		if (!(player.level().getEntity(payload.shipId()) instanceof ShipEntity ship) || ship.structure() == null) {
			return;
		}
		BlockState state = ship.structure().get(payload.local());
		if (state == null) return;
		// Reach check against the block's current world position (plus slack for latency).
		double reach = player.blockInteractionRange() + 3;
		if (player.getEyePosition().distanceToSqr(ship.blockCenter(payload.local())) > reach * reach) {
			return;
		}

		boolean helm = state.getBlock() instanceof HelmBlock;
		// Ownership: the Helm needs owner/crew (an ownerless ship is claimed by its first pilot);
		// everything else is open unless the owner locked the ship. Fuelling is always allowed.
		if (helm && !ship.canPilot(player)) {
			player.sendOverlayMessage(Component.translatable("message.starwake.ship.not_yours", ship.ownerName()));
			return;
		}
		if (!helm && !(state.getBlock() instanceof FuelTankBlock && isFuel(player.getMainHandItem())) && !ship.canUse(player)) {
			player.sendOverlayMessage(Component.translatable("message.starwake.ship.locked", ship.ownerName()));
			return;
		}
		if (helm && ship.owner() == null) ship.setOwner(player);
		if (state.getBlock() instanceof HelmBlock || state.getBlock() instanceof com.starwake.ship.block.SeatBlock) {
			if (!ship.seat(player, payload.local())) {
				player.sendOverlayMessage(Component.translatable("message.starwake.helm_taken"));
			}
		} else if (state.getBlock() instanceof FuelTankBlock && isFuel(player.getMainHandItem())) {
			ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
			// M8f: pour a Gas Cylinder of propellant (H2, O2, CH4) or fusion fuel (D2, He3) into the ship's tanks.
			var gas = stack.get(com.starwake.registry.ModComponents.GAS);
			if (gas != null && com.starwake.ship.fuel.Propellants.SPECIES.contains(gas.species())) {
				double kg = gas.amount() / (double) com.starwake.process.Chemicals.KG;
				ship.addPropellant(gas.species(), kg);
				stack.remove(com.starwake.registry.ModComponents.GAS);
			}
			int units = ShipFuels.unitsFor(stack);
			if (units > 0) {
				int room = (ship.fuelCapacity() - ship.fuel()) / units;
				int items = Math.min(player.isShiftKeyDown() ? stack.getCount() : 1, room);
				if (items > 0) {
					stack.shrink(items);
					ship.addFuel(items * units);
				}
			}
			player.sendOverlayMessage(Component.translatable("message.starwake.fuel_tank.gauge_fusion", ship.fuel(), ship.fuelCapacity(),
					ship.fusion(), ship.fusionCapacity()));
		} else if (state.getBlock() instanceof com.starwake.ship.block.ShipWeaponBlock
				&& player.getMainHandItem().is(com.starwake.registry.ModItems.AUTOCANNON_ROUNDS)) {
			// M11: rounds go into the ship's magazine (shared by every gun of that calibre).
			int loaded = ship.weapons.load(payload.local(), player.getMainHandItem());
			var spec = com.starwake.ship.weapon.WeaponSpec.of(state.getBlock());
			String ammo = spec == null ? "" : spec.ammo().map(Object::toString).orElse("");
			player.sendOverlayMessage(loaded > 0
					? Component.translatable("message.starwake.weapon.loaded", loaded, ship.weapons.rounds(ammo))
					: Component.translatable("message.starwake.weapon.magazine", ship.weapons.rounds(ammo), ShipWeapons.MAGAZINE));
		} else if (isPlate(player.getMainHandItem()) && !(ship.components().contains(payload.local())
				&& ship.power.health(payload.local()) < 0.999f) && ship.hull() < ship.hullMax()) {
			// M11: a plate on the hull patches the hull point pool (5% of it, at least 20 points, a plate).
			float next = Math.min(ship.hullMax(), ship.hull() + Math.max(20, ship.hullMax() * 0.05f));
			ship.getEntityData().set(ShipEntity.HULL, next);
			if (!player.isCreative()) player.getItemInHand(InteractionHand.MAIN_HAND).shrink(1);
			player.level().playSound(null, BlockPos.containing(ship.blockCenter(payload.local())), net.minecraft.sounds.SoundEvents.ANVIL_USE,
					net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.2f);
			player.sendOverlayMessage(Component.translatable("message.starwake.hull.repaired", Math.round(next), Math.round(ship.hullMax())));
		} else if (ship.components().contains(payload.local()) && isPlate(player.getMainHandItem())) {
			// M10: a metal plate patches a worn part (25% a plate).
			ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
			if (ship.power.repair(payload.local())) {
				if (!player.isCreative()) stack.shrink(1);
				player.level().playSound(null, BlockPos.containing(ship.blockCenter(payload.local())), net.minecraft.sounds.SoundEvents.ANVIL_USE,
						net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.4f);
				player.sendOverlayMessage(Component.translatable("message.starwake.power.repaired", state.getBlock().getName(),
						Math.round(ship.power.health(payload.local()) * 100)));
			} else {
				player.sendOverlayMessage(Component.translatable("message.starwake.power.like_new"));
			}
		} else {
			useInPlot(player, ship, payload);
		}
	}

	private static boolean isPlate(ItemStack stack) {
		return stack.getItem() instanceof com.starwake.metal.MetalItem metal && metal.form() == com.starwake.metal.MetalForm.PLATE;
	}
}
