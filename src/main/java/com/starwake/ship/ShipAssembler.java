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
		ship.setPos(min.getX(), min.getY(), min.getZ());
		ship.initialize(new ShipStructure(entries), helm.subtract(min), forward, fuel, capacity);
		level.addFreshEntity(ship);
		if (pilot != null) {
			pilot.startRiding(ship);
			pilot.sendOverlayMessage(Component.translatable("message.starwake.assembled", entries.size()));
		}
		return ship;
	}

	/**
	 * Places the ship's blocks back into the world at the nearest whole-block position.
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
		BlockPos origin = BlockPos.containing(ship.position().add(0.5, 0.5, 0.5));
		for (ShipStructure.Entry entry : ship.structure().entries()) {
			BlockPos target = origin.offset(entry.pos());
			if (!level.getBlockState(target).canBeReplaced()) {
				return Component.translatable("message.starwake.disassemble.obstructed", target.getX(), target.getY(), target.getZ());
			}
		}

		Vec3 snap = Vec3.atLowerCornerOf(origin).subtract(ship.position());
		AABB zone = ship.getBoundingBox().expandTowards(0, 1.0, 0);
		List<Entity> aboard = level.getEntities(ship, zone, e -> !(e instanceof ShipEntity));
		ship.ejectPassengers();

		int fuel = ship.fuel();
		for (ShipStructure.Entry entry : ship.structure().entries()) {
			BlockPos target = origin.offset(entry.pos());
			level.setBlock(target, entry.state(), SILENT);
			entry.blockEntity().ifPresent(tag -> {
				BlockEntity blockEntity = level.getBlockEntity(target);
				if (blockEntity != null) {
					blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
					blockEntity.setChanged();
				}
			});
		}
		// Pour the ship's fuel back into its tanks.
		for (ShipStructure.Entry entry : ship.structure().entries()) {
			if (fuel <= 0) break;
			if (level.getBlockEntity(origin.offset(entry.pos())) instanceof FuelTankBlockEntity tank) {
				fuel -= tank.refill(fuel);
			}
		}
		// Shift everyone aboard by the same snap so nobody ends up inside a block.
		for (Entity entity : aboard) {
			entity.setPos(entity.position().add(snap));
		}
		ship.discard();
		return null;
	}
}
