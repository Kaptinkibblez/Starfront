package com.starwake.ship.yard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The shipyard (M9): one hidden void dimension, {@code starwake:shipyard}, where every assembled ship keeps its
 * blocks as real blocks in real chunks, so block entities tick, redstone runs and every block works aboard.
 * Each ship owns a <b>plot</b>: a 1024 x 1024 column far out at x, z >= {@link #ORIGIN}; ship-local (0,0,0) sits
 * at the plot's centre at y 0. The ship entity flies around a planet or space; its blocks never move, so crossing
 * dimensions only moves the entity (Valkyrien Skies' never-built "store ships outside every world" design).
 *
 * <p>The ship's {@link ShipStructure} stays as a snapshot for physics, collision, rendering and syncing clients;
 * the plot is the truth. Block changes in a plot (a door opening, a furnace lighting, a block broken by a
 * player) reach the ship through {@link #onBlockChanged}, and the ship folds them into its snapshot once a tick.
 * Entities spawned in the shipyard (drops, XP) are moved to the ship's real position ({@link #relocate}).
 * Plot chunks are kept loaded by a short ticket the ship renews while it's loaded itself.
 */
public final class Shipyard {
	public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, StarwakeMod.id("shipyard"));
	/** Plot side in blocks, the first plot's corner, and plots per row. */
	public static final int PLOT = 1024, ORIGIN = 1 << 20, COLUMNS = 256;
	/** Loading + simulation + keeps the dimension ticking; expires 5 s after the ship stops renewing it. */
	public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, StarwakeMod.id("ship_plot"),
			new TicketType(100L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
	/** Placing/removing plot blocks without drops, neighbour updates or block-entity side effects. */
	public static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;

	/** Ships whose plots are loaded, by plot index (server). */
	private static final Int2ObjectMap<ShipEntity> LOADED = new Int2ObjectOpenHashMap<>();
	/** Set while we write plots ourselves, so the change listener doesn't echo our own writes. */
	private static int quiet;

	private Shipyard() {}

	public static void init() {
		// Class loading registers the ticket type; nothing else to do at startup.
	}

	// --- Geometry ---------------------------------------------------------------------------------

	/** World position of ship-local (0,0,0) in plot {@code index}. */
	public static BlockPos centre(int index) {
		int col = index % COLUMNS, row = index / COLUMNS;
		return new BlockPos(ORIGIN + col * PLOT + PLOT / 2, 0, ORIGIN + row * PLOT + PLOT / 2);
	}

	/** The plot a shipyard position belongs to, or -1. */
	public static int indexAt(BlockPos pos) {
		return indexAt(pos.getX(), pos.getZ());
	}

	public static int indexAt(double x, double z) {
		double dx = x - ORIGIN, dz = z - ORIGIN;
		if (dx < 0 || dz < 0) return -1;
		int col = (int) (dx / PLOT), row = (int) (dz / PLOT);
		return col >= COLUMNS ? -1 : row * COLUMNS + col;
	}

	/** True for positions inside the plot grid (any dimension: used by the interaction-range hook). */
	public static boolean inYard(BlockPos pos) {
		return pos.getX() >= ORIGIN && pos.getZ() >= ORIGIN;
	}

	public static BlockPos toPlot(int index, BlockPos local) {
		return centre(index).offset(local);
	}

	public static BlockPos toLocal(int index, BlockPos plot) {
		return plot.subtract(centre(index));
	}

	public static Vec3 toLocal(int index, Vec3 plot) {
		BlockPos c = centre(index);
		return plot.subtract(c.getX(), c.getY(), c.getZ());
	}

	public static Vec3 toPlot(int index, Vec3 local) {
		BlockPos c = centre(index);
		return local.add(c.getX(), c.getY(), c.getZ());
	}

	public static @Nullable ServerLevel level(MinecraftServer server) {
		return server.getLevel(KEY);
	}

	public static boolean isShipyard(Level level) {
		return level.dimension() == KEY;
	}

	// --- Plots --------------------------------------------------------------------------------------

	/** Saved plot allocation: the next fresh index and the indices freed by disassembled ships. */
	public static final class State extends SavedData {
		public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("next").forGetter(s -> s.next),
				Codec.INT.listOf().fieldOf("free").forGetter(s -> s.free)
		).apply(i, State::new));
		public static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("shipyard"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
		int next;
		final List<Integer> free;

		State() {
			this(0, List.of());
		}

		State(int next, List<Integer> free) {
			this.next = next;
			this.free = new ArrayList<>(free);
		}
	}

	private static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}

	public static int allocate(MinecraftServer server) {
		State state = state(server);
		state.setDirty();
		return state.free.isEmpty() ? state.next++ : state.free.removeLast();
	}

	public static void free(MinecraftServer server, int index) {
		if (index < 0) return;
		State state = state(server);
		if (!state.free.contains(index)) state.free.add(index);
		state.setDirty();
		LOADED.remove(index);
	}

	// --- Loaded ships -------------------------------------------------------------------------------

	public static void track(ShipEntity ship, int index) {
		LOADED.put(index, ship);
	}

	public static void untrack(ShipEntity ship, int index) {
		if (LOADED.get(index) == ship) LOADED.remove(index);
	}

	public static @Nullable ShipEntity shipAt(BlockPos plotPos) {
		int index = indexAt(plotPos);
		return index < 0 ? null : LOADED.get(index);
	}

	/** Keeps a plot's chunks loaded and ticking for the next 5 s. */
	public static void ticket(ServerLevel yard, int index, ShipStructure structure) {
		BlockPos lo = toPlot(index, structure.min()), hi = toPlot(index, structure.max());
		ChunkPos centre = ChunkPos.containing(new BlockPos((lo.getX() + hi.getX()) / 2, 0, (lo.getZ() + hi.getZ()) / 2));
		int radius = Math.max(hi.getX() - lo.getX(), hi.getZ() - lo.getZ()) / 32 + 2;
		yard.getChunkSource().addTicketWithRadius(TICKET, centre, Math.min(radius, 32));
	}

	// --- Moving blocks in and out ------------------------------------------------------------------

	/** Writes structure entries into a plot (assembly, migration of old ships). Block-entity data is restored. */
	public static void write(ServerLevel yard, int index, Collection<ShipStructure.Entry> entries) {
		quiet++;
		try {
			for (ShipStructure.Entry entry : entries) {
				BlockPos pos = toPlot(index, entry.pos());
				yard.getChunk(pos); // load or create the void chunk
				yard.setBlock(pos, entry.state(), QUIET);
			}
			for (ShipStructure.Entry entry : entries) {
				if (entry.blockEntity().isEmpty()) continue;
				BlockEntity blockEntity = yard.getBlockEntity(toPlot(index, entry.pos()));
				if (blockEntity != null) {
					blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, yard.registryAccess(), entry.blockEntity().get()));
					blockEntity.setChanged();
				}
			}
		} finally {
			quiet--;
		}
	}

	/** Reads the current blocks (and fresh block-entity data) at these local positions. */
	public static List<ShipStructure.Entry> read(ServerLevel yard, int index, Collection<BlockPos> locals) {
		List<ShipStructure.Entry> entries = new ArrayList<>(locals.size());
		for (BlockPos local : locals) {
			BlockPos pos = toPlot(index, local);
			BlockState state = yard.getBlockState(pos);
			if (state.isAir()) continue;
			BlockEntity blockEntity = yard.getBlockEntity(pos);
			Optional<CompoundTag> data = blockEntity == null ? Optional.empty() : Optional.of(blockEntity.saveWithFullMetadata(yard.registryAccess()));
			entries.add(new ShipStructure.Entry(local.immutable(), state, data));
		}
		return entries;
	}

	/** Removes blocks from a plot without drops (disassembly, ships that are gone). */
	public static void clear(ServerLevel yard, int index, Collection<BlockPos> locals) {
		quiet++;
		try {
			for (BlockPos local : locals) {
				BlockPos pos = toPlot(index, local);
				yard.removeBlockEntity(pos);
				yard.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
			}
		} finally {
			quiet--;
		}
	}

	/** Sets one block in a ship's plot as a normal level change (neighbours update; the ship hears about it). */
	public static void set(ShipEntity ship, BlockPos local, BlockState state) {
		ServerLevel yard = ship.level().getServer() == null ? null : level(ship.level().getServer());
		if (yard == null || ship.plot() < 0) return;
		yard.setBlock(toPlot(ship.plot(), local), state, Block.UPDATE_ALL);
	}

	// --- Hooks (mixins) --------------------------------------------------------------------------

	/** Every effective block change in any server level ends up here (ServerLevelMixin). */
	public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
		if (quiet > 0 || level.dimension() != KEY) return;
		ShipEntity ship = shipAt(pos);
		if (ship != null) ship.onPlotChange(toLocal(ship.plot(), pos), now);
	}

	/**
	 * An entity spawned in the shipyard (an item drop, an XP orb, a projectile) appears at the matching point of
	 * the real ship instead, moving with it. Entities of a plot whose ship isn't loaded are dropped.
	 *
	 * @return true if the original spawn should be cancelled
	 */
	public static boolean relocate(ServerLevel yard, Entity entity) {
		if (yard.dimension() != KEY || entity instanceof net.minecraft.world.entity.player.Player) return false;
		ShipEntity ship = shipAt(entity.blockPosition());
		if (ship == null || !(ship.level() instanceof ServerLevel world)) return true;
		Vec3 local = toLocal(ship.plot(), entity.position());
		Vec3 at = ship.toWorld(local);
		Vec3 v = entity.getDeltaMovement();
		Vector3f vw = ship.orientation().transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		Entity copy = entity.getType().create(world, EntitySpawnReason.LOAD);
		if (copy == null) return true;
		copy.restoreFrom(entity);
		copy.setPos(at.x, at.y, at.z);
		copy.setDeltaMovement(new Vec3(vw.x, vw.y, vw.z).add(ship.lastMove()));
		world.addFreshEntity(copy);
		return true;
	}

	/**
	 * The level and position a shipyard block really sits at (its ship's level and world position), for machines
	 * that read their surroundings (sunlight, air, biome). Anything else returns {@code (level, pos)} unchanged.
	 */
	public static Located locate(Level level, BlockPos pos) {
		if (level.dimension() == KEY && !level.isClientSide()) {
			ShipEntity ship = shipAt(pos);
			if (ship != null) return new Located(ship.level(), BlockPos.containing(ship.blockCenter(toLocal(ship.plot(), pos))), ship);
		}
		return new Located(level, pos, null);
	}

	/** A point in the shipyard as a point on its (loaded) ship in the world, or null. */
	public static @Nullable Point toWorld(double x, double y, double z) {
		int index = indexAt(x, z);
		ShipEntity ship = index < 0 ? null : LOADED.get(index);
		if (ship == null) return null;
		return new Point(ship, ship.toWorld(toLocal(index, new Vec3(x, y, z))));
	}

	public record Point(ShipEntity ship, Vec3 pos) {}

	/** Removes a block from a ship's plot as a normal change (neighbours update) but without drops or spilling contents. */
	public static void remove(ShipEntity ship, BlockPos local) {
		ServerLevel yard = ship.level().getServer() == null ? null : level(ship.level().getServer());
		if (yard == null || ship.plot() < 0) return;
		yard.setBlock(toPlot(ship.plot(), local), Blocks.AIR.defaultBlockState(),
				Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
	}

	/** Where a block really is: its level, world position and (for ship blocks) the ship. */
	public record Located(Level level, BlockPos pos, @Nullable ShipEntity ship) {}
}
