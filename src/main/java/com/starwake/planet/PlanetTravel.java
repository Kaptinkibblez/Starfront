package com.starwake.planet;

import com.starwake.StarwakeMod;
import com.starwake.registry.ModBlocks;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Moves players between planets and home. Commands use this today; in Milestone 3 the rocket's
 * launch sequence calls the same methods.
 *
 * When a player leaves a non-planet dimension, their position is saved as a "return point"
 * (persisted on the player), so {@link #returnHome} brings them back where they left.
 */
public final class PlanetTravel {
	/** Where the player was before travelling to a planet. Saved with the player. */
	public static final AttachmentType<GlobalPos> RETURN_POINT = AttachmentRegistry.<GlobalPos>builder()
			.persistent(GlobalPos.CODEC)
			.copyOnDeath()
			.buildAndRegister(StarwakeMod.id("return_point"));

	/** Landing coordinates on a planet. The rocket will choose real landing sites later. */
	private static final int LANDING_X = 0;
	private static final int LANDING_Z = 0;

	private PlanetTravel() {}

	public static void init() {
		// Static init registers RETURN_POINT; nothing else to do yet.
	}

	/**
	 * Sends a player to a planet's landing site.
	 *
	 * @return null on success, or a translation key describing the failure
	 */
	public static @Nullable String travelTo(ServerPlayer player, Planet planet) {
		MinecraftServer server = player.level().getServer();
		ServerLevel target = server.getLevel(planet.dimension());
		if (target == null) {
			return "commands.starwake.planet.missing_dimension";
		}

		// Remember where the player came from when leaving a home (non-Starwake) dimension.
		if (!player.level().dimension().identifier().getNamespace().equals(StarwakeMod.MOD_ID)) {
			player.setAttached(RETURN_POINT, GlobalPos.of(player.level().dimension(), player.blockPosition()));
		}

		BlockPos landing = findLandingSite(target, LANDING_X, LANDING_Z);
		teleport(player, target, landing);
		return null;
	}

	/** Returns a player to their saved return point, or to world spawn if there is none. */
	public static void returnHome(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		GlobalPos home = player.getAttached(RETURN_POINT);
		ServerLevel level = home == null ? null : server.getLevel(home.dimension());
		if (home == null || level == null) {
			GlobalPos spawn = server.getRespawnData().globalPos();
			level = server.getLevel(spawn.dimension());
			if (level == null) {
				level = server.overworld();
			}
			home = GlobalPos.of(level.dimension(), level.getRespawnData().globalPos().pos());
		}
		player.removeAttached(RETURN_POINT);
		// Re-check the surface: terrain may have changed since the player left.
		BlockPos pos = home.pos();
		teleport(player, level, findLandingSite(level, pos.getX(), pos.getZ()));
	}

	private static void teleport(ServerPlayer player, ServerLevel level, BlockPos pos) {
		player.teleport(new TeleportTransition(level, Vec3.atBottomCenterOf(pos), Vec3.ZERO,
				player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
		player.fallDistance = 0;
	}

	/**
	 * Finds the surface at (x, z), generating the chunk if needed. If the column is empty (void
	 * or a bottomless chasm), builds a small Hull Plating platform so the player never spawns
	 * over nothing.
	 */
	private static BlockPos findLandingSite(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4); // force generation so the heightmap is valid
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (y > level.getMinY() + 1) {
			return new BlockPos(x, y, z);
		}

		// Never below 64 above the floor: void dimensions (space) report a meaningless sea level.
		int platformY = Math.max(level.getSeaLevel() + 1, level.getMinY() + 64);
		BlockState plating = ModBlocks.HULL_PLATING.defaultBlockState();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(new BlockPos(x + dx, platformY - 1, z + dz), plating);
			}
		}
		return new BlockPos(x, platformY, z);
	}
}
