package com.starwake.client.ship;

import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Keeps rain and snow out of ships (M9 pass 2). Vanilla stops precipitation at the world's motion-blocking
 * heightmap, which knows nothing about ship blocks, so it fell straight through hulls. Once per client tick this
 * records, for every world column a nearby ship covers, the top of its highest solid block there; the weather
 * renderer and the rain-splash tick ({@code WeatherMixin}) take the higher of that and the heightmap. A ship
 * therefore shelters its cabin and the ground under it, at any attitude.
 */
public final class ShipRain {
	/** Packed column (x, z) -> first free Y above the ship there. */
	private static final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
	private static long builtTick = Long.MIN_VALUE;
	private static ClientLevel builtLevel;

	static {
		tops.defaultReturnValue(Integer.MIN_VALUE);
	}

	private ShipRain() {}

	/** The height rain stops at in column (x, z): vanilla's heightmap value or the top of a ship over it. */
	public static int height(ClientLevel level, int x, int z, int heightmap) {
		refresh(level);
		if (tops.isEmpty()) return heightmap;
		return Math.max(heightmap, tops.get(BlockPos.asLong(x, 0, z)));
	}

	private static void refresh(ClientLevel level) {
		long tick = level.getGameTime();
		if (tick == builtTick && level == builtLevel) return;
		builtTick = tick;
		builtLevel = level;
		tops.clear();
		if (level.getRainLevel(1.0f) <= 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		int radius = minecraft.options.weatherRadius().get() + 2;
		AABB area = new AABB(camera, camera).inflate(radius, 512, radius);
		Vector3f c = new Vector3f();
		for (Entity entity : level.getEntities((Entity) null, area.inflate(64), e -> e instanceof ShipEntity)) {
			ShipEntity ship = (ShipEntity) entity;
			ShipStructure structure = ship.structure();
			if (structure == null || !ship.getBoundingBox().intersects(area)) continue;
			Quaternionf rotation = new Quaternionf(ship.orientation());
			Vec3 pivot = ship.pivot(), origin = ship.position();
			for (ShipStructure.Entry entry : structure.entries()) {
				if (entry.state().isAir() || entry.state().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) continue;
				BlockPos local = entry.pos();
				c.set((float) (local.getX() + 0.5 - pivot.x), (float) (local.getY() + 0.5 - pivot.y), (float) (local.getZ() + 0.5 - pivot.z));
				rotation.transform(c);
				int x = Mth.floor(origin.x + c.x), z = Mth.floor(origin.z + c.z);
				if (Math.abs(x - camera.x) > radius || Math.abs(z - camera.z) > radius) continue;
				int top = Mth.floor(origin.y + c.y + 0.5) + 1;
				long key = BlockPos.asLong(x, 0, z);
				if (top > tops.get(key)) tops.put(key, top);
			}
		}
	}
}
