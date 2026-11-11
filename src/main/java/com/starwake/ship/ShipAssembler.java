package com.starwake.ship;

import com.starwake.registry.ModEntities;
import com.starwake.ship.block.FuelTankBlockEntity;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.scan.PreflightReport;
import com.starwake.ship.scan.ShipScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Turns a built structure into a {@link ShipEntity} (assemble) and back (disassemble).
 *
 * Blocks are lifted out without drops or neighbour updates, so attached blocks (torches,
 * buttons) don't pop off and containers keep their contents (saved as block-entity data).
 */
public final class ShipAssembler {
	/** Remove/place blocks silently: no drops, no shape updates, no block-entity side effects. */
	private static final int SILENT = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
			| Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	private ShipAssembler() {}

	/** Assembles from the console: the player becomes the pilot. */
	public static void assemble(ServerPlayer player, BlockPos helm) {
		if (assemble(player.level(), helm, player) == null) {
			ShipConsole.open(player, helm); // refresh so the player sees why it failed
		}
	}

	/**
	 * Assembles the ship at {@code helm} if it passes the pre-flight check.
	 *
	 * @param pilot who takes the helm afterwards, or null (admin command)
	 * @return the new ship, or null if the check failed
	 */
	public static @Nullable ShipEntity assemble(ServerLevel level, BlockPos helm, @Nullable ServerPlayer pilot) {
		ShipScanner.ShipScan scan = ShipScanner.scan(level, helm);
		PreflightReport report = PreflightReport.analyze(level, scan);
		if (!report.canFly()) {
			return null;
		}

		var forward = level.getBlockState(helm).getValue(HelmBlock.FACING);
		BoundingBox box = BoundingBox.encapsulatingPositions(scan.blocks()).orElseThrow();
		BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
		List<ShipStructure.Entry> entries = new ArrayList<>(scan.blocks().size());
		int fuel = 0, capacity = 0;

		for (BlockPos pos : scan.blocks()) {
			BlockState state = level.getBlockState(pos);
			BlockEntity blockEntity = level.getBlockEntity(pos);
			Optional<CompoundTag> data = Optional.empty();
			if (blockEntity instanceof FuelTankBlockEntity tank) {
				capacity += tank.capacity();
				fuel += tank.drain(); // fuel moves into the ship's shared pool
			}
			if (blockEntity != null) {
				data = Optional.of(blockEntity.saveWithFullMetadata(level.registryAccess()));
			}
			entries.add(new ShipStructure.Entry(pos.subtract(min), state, data));
		}
		for (BlockPos pos : scan.blocks()) {
			level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), SILENT);
		}

		ShipEntity ship = ModEntities.SHIP.create(level, EntitySpawnReason.TRIGGERED);
		if (ship == null) return null;
		ship.initialize(new ShipStructure(entries), helm.subtract(min), forward, fuel, capacity);
		// The entity position is the pivot (structure centre), not the min corner.
		ship.setPos(min.getX() + ship.pivot().x, min.getY() + ship.pivot().y, min.getZ() + ship.pivot().z);
		if (SpaceTravel.isSpace(level)) {
			ship.setOrbiting(net.minecraft.world.level.Level.OVERWORLD); // built in orbit: default to home
		}
		level.addFreshEntity(ship);
		if (pilot != null) {
			ship.seat(pilot, helm.subtract(min));
			pilot.sendOverlayMessage(Component.translatable("message.starwake.assembled", entries.size()));
		}
		return ship;
	}

	/**
	 * Places the ship's blocks back into the world. The ship must be stationary, level, and
	 * facing a cardinal direction (blocks are rotated to match its heading).
	 *
	 * @return null on success, otherwise the reason it can't disassemble
	 */
	public static @Nullable Component disassemble(ShipEntity ship) {
		if (!(ship.level() instanceof ServerLevel level) || ship.structure() == null) {
			return Component.translatable("message.starwake.disassemble.failed");
		}
		if (ship.getDeltaMovement().lengthSqr() > 1.0e-4) {
			return Component.translatable("message.starwake.disassemble.moving");
		}
		// Must be roughly level (within 20 degrees); it then squares up to the nearest of N/E/S/W.
		if (ship.worldDirection(net.minecraft.core.Direction.UP).y < Math.cos(Math.toRadians(20))) {
			return Component.translatable("message.starwake.disassemble.not_level");
		}
		Vec3 north = ship.worldDirection(net.minecraft.core.Direction.NORTH);
		net.minecraft.core.Direction heading = net.minecraft.core.Direction.getApproximateNearest(north.x, 0, north.z);
		float yaw = switch (heading) { // rotation about +Y that maps local north onto the heading
			case WEST -> (float) (Math.PI / 2);
			case SOUTH -> (float) Math.PI;
			case EAST -> (float) (-Math.PI / 2);
			default -> 0f;
		};
		if (!ship.trySetOrientation(new org.joml.Quaternionf().rotationY(yaw))) {
			return Component.translatable("message.starwake.disassemble.obstructed_rotation");
		}
		Rotation rotation = Rotation.NONE;
		for (Rotation r : Rotation.values()) {
			if (r.rotate(net.minecraft.core.Direction.NORTH) == heading) rotation = r;
		}

		// Each block lands in the world cell containing its (snapped) centre.
		List<BlockPos> targets = new ArrayList<>();
		for (ShipStructure.Entry entry : ship.structure().entries()) {
			BlockPos target = BlockPos.containing(ship.blockCenter(entry.pos()));
			if (!level.getBlockState(target).canBeReplaced()) {
				return Component.translatable("message.starwake.disassemble.obstructed", target.getX(), target.getY(), target.getZ());
			}
			targets.add(target);
		}

		AABB zone = ship.getBoundingBox().expandTowards(0, 1.0, 0);
		List<Entity> aboard = level.getEntities(ship, zone, e -> !(e instanceof ShipEntity) && ship.isSupporting(e));
		ship.ejectPassengers();

		int fuel = ship.fuel();
		List<ShipStructure.Entry> entries = ship.structure().entries();
		for (int i = 0; i < entries.size(); i++) {
			ShipStructure.Entry entry = entries.get(i);
			BlockPos target = targets.get(i);
			level.setBlock(target, entry.state().rotate(rotation), SILENT);
			entry.blockEntity().ifPresent(tag -> {
				BlockEntity blockEntity = level.getBlockEntity(target);
				if (blockEntity != null) {
					blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
					blockEntity.setChanged();
				}
			});
		}
		// Pour the ship's fuel back into its tanks.
		for (BlockPos target : targets) {
			if (fuel <= 0) break;
			if (level.getBlockEntity(target) instanceof FuelTankBlockEntity tank) {
				fuel -= tank.refill(fuel);
			}
		}
		// Nudge everyone aboard up slightly so nobody is left inside a block after snapping.
		for (Entity entity : aboard) {
			entity.setPos(entity.position().add(0, 0.05, 0));
		}
		ship.discard();
		return null;
	}
}
