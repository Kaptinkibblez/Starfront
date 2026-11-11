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

/**
 * Server side of interacting with an assembled ship: syncing its blocks to clients, and
 * handling right-clicks on ship blocks (found client-side by a ship-space raycast).
 */
public final class ShipInteraction {
	private ShipInteraction() {}

	public static void init() {
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof ShipEntity ship && ship.structure() != null) {
				ServerPlayNetworking.send(player, new ShipStructurePayload(ship.getId(), ship.structure()));
			}
		});
	}

	/** Doors (both halves), trapdoors and gates swing open and shut aboard a flying ship. */
	private static void toggleOpen(ShipEntity ship, BlockPos local, BlockState state) {
		boolean open = !state.getValue(BlockStateProperties.OPEN);
		ship.updateBlock(local, state.setValue(BlockStateProperties.OPEN, open));
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? local.above() : local.below();
			BlockState otherState = ship.structure().get(other);
			if (otherState != null && otherState.hasProperty(BlockStateProperties.OPEN)) {
				ship.updateBlock(other, otherState.setValue(BlockStateProperties.OPEN, open));
			}
		}
		ship.level().playSound(null, BlockPos.containing(ship.blockCenter(local)),
				open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 1.0f, 1.0f);
	}

	/** Chests and barrels aboard open a normal 27-slot inventory backed by the ship's data. */
	private static void openContainer(ServerPlayer player, ShipEntity ship, BlockPos local, BlockState state) {
		var container = ship.container(local, 27);
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory, container),
				state.getBlock().getName()));
		ship.level().playSound(null, BlockPos.containing(ship.blockCenter(local)),
				state.getBlock() instanceof BarrelBlock ? SoundEvents.BARREL_OPEN : SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
	}

	/** Blocks a right-click uses rather than builds against. */
	private static boolean isUsable(BlockState state, ServerPlayer player) {
		return state.getBlock() instanceof HelmBlock || state.getBlock() instanceof com.starwake.ship.block.SeatBlock
				|| (state.getBlock() instanceof FuelTankBlock && ShipFuels.unitsFor(player.getMainHandItem()) > 0)
				|| (state.hasProperty(BlockStateProperties.OPEN)
						&& (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)))
				|| state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
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

		// Milestone 4: sneak + block in hand always builds; otherwise blocks you can't use build too.
		boolean holdingBlock = player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem
				|| player.getOffhandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
		if (holdingBlock && (player.isSecondaryUseActive() || !isUsable(state, player))) {
			for (var hand : net.minecraft.world.InteractionHand.values()) {
				if (com.starwake.ship.edit.ShipEditing.place(player, ship, hand, payload.local(), payload.face(), payload.hit())) return;
			}
			return;
		}
		boolean helm = state.getBlock() instanceof HelmBlock;
		// Ownership: the Helm needs owner/crew (an ownerless ship is claimed by its first pilot);
		// everything else is open unless the owner locked the ship. Fuelling is always allowed.
		if (helm && !ship.canPilot(player)) {
			player.sendOverlayMessage(Component.translatable("message.starwake.ship.not_yours", ship.ownerName()));
			return;
		}
		if (!helm && !(state.getBlock() instanceof FuelTankBlock) && !ship.canUse(player)) {
			player.sendOverlayMessage(Component.translatable("message.starwake.ship.locked", ship.ownerName()));
			return;
		}
		if (helm && ship.owner() == null) ship.setOwner(player);
		if (state.getBlock() instanceof HelmBlock || state.getBlock() instanceof com.starwake.ship.block.SeatBlock) {
			if (!ship.seat(player, payload.local())) {
				player.sendOverlayMessage(Component.translatable("message.starwake.helm_taken"));
			}
		} else if (state.getBlock() instanceof FuelTankBlock) {
			ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
			int units = ShipFuels.unitsFor(stack);
			if (units > 0) {
				int room = (ship.fuelCapacity() - ship.fuel()) / units;
				int items = Math.min(player.isShiftKeyDown() ? stack.getCount() : 1, room);
				if (items > 0) {
					stack.shrink(items);
					ship.addFuel(items * units);
				}
			}
			player.sendOverlayMessage(Component.translatable("message.starwake.fuel_tank.gauge", ship.fuel(), ship.fuelCapacity()));
		} else if (state.hasProperty(BlockStateProperties.OPEN)
				&& (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES))) {
			toggleOpen(ship, payload.local(), state);
		} else if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock) {
			openContainer(player, ship, payload.local(), state);
		} else {
			player.sendOverlayMessage(Component.translatable("message.starwake.edit.hint"));
		}
	}
}
