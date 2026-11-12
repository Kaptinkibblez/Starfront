package com.starwake.machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The electric grid (M8b). A network is a connected group of cables and energy nodes (machines
 * touching each other connect directly). Networks are found by a bounded flood fill when a node
 * first asks, cached by position, and thrown away when anything in them changes (a cable or
 * machine placed, broken or unloaded); their nodes rebuild them on their next tick. Nothing is
 * saved: energy lives in the nodes.
 *
 * <p>Each tick, per network: generators' output goes to consumers first (shared in proportion to
 * need), then batteries cover any shortfall or soak up any surplus. All flow is capped at the
 * network's rating, the rating of its weakest cable (no cables = machines touching = unlimited).
 */
public final class PowerNetworks {
	public static final int MAX_SIZE = 4096;

	/** One network. Stats are from the last tick, for screens and probes. */
	public static final class Network {
		final Set<BlockPos> cables = new HashSet<>();
		final List<BlockPos> nodes = new ArrayList<>();
		long ratingPerTick = Long.MAX_VALUE;
		public long lastGeneration, lastDemand, lastDelivered, lastStored, lastCapacity;

		public int cableCount() {
			return cables.size();
		}

		public int nodeCount() {
			return nodes.size();
		}

		public double ratingKw() {
			return ratingPerTick == Long.MAX_VALUE ? Double.POSITIVE_INFINITY : EnergyNode.kw(ratingPerTick);
		}
	}

	private static final class Grid {
		final Map<BlockPos, Network> byPos = new HashMap<>();
		final List<Network> networks = new ArrayList<>();
	}

	private static final Map<Level, Grid> GRIDS = new WeakHashMap<>();

	private PowerNetworks() {}

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(PowerNetworks::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> GRIDS.clear());
	}

	private static Grid grid(Level level) {
		return GRIDS.computeIfAbsent(level, l -> new Grid());
	}

	/** The network a node belongs to, building it if needed. */
	public static Network networkOf(ServerLevel level, BlockPos pos) {
		Grid grid = grid(level);
		Network network = grid.byPos.get(pos);
		if (network == null) {
			network = build(level, pos.immutable());
			for (BlockPos p : network.cables) grid.byPos.put(p, network);
			for (BlockPos p : network.nodes) grid.byPos.put(p, network);
			grid.networks.add(network);
		}
		return network;
	}

	/** Something at pos changed (cable or node placed, removed or unloaded): drop the networks around it. */
	public static void invalidate(Level level, BlockPos pos) {
		if (level.isClientSide()) return;
		Grid grid = grid(level);
		Set<Network> dead = new HashSet<>();
		Network here = grid.byPos.get(pos);
		if (here != null) dead.add(here);
		for (Direction d : Direction.values()) {
			Network n = grid.byPos.get(pos.relative(d));
			if (n != null) dead.add(n);
		}
		for (Network n : dead) {
			n.cables.forEach(grid.byPos::remove);
			n.nodes.forEach(grid.byPos::remove);
			grid.networks.remove(n);
		}
	}

	private static Network build(ServerLevel level, BlockPos start) {
		Network network = new Network();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty() && seen.size() < MAX_SIZE) {
			BlockPos pos = queue.poll();
			if (!level.isLoaded(pos)) continue;
			BlockState state = level.getBlockState(pos);
			boolean member = false;
			if (state.getBlock() instanceof CableBlock cable) {
				network.cables.add(pos);
				network.ratingPerTick = Math.min(network.ratingPerTick, EnergyNode.perTick(MachineSpec.of(cable).rating()));
				member = true;
			} else if (level.getBlockEntity(pos) instanceof EnergyNode) {
				network.nodes.add(pos);
				member = true;
			}
			if (!member) continue;
			for (Direction d : Direction.values()) {
				BlockPos next = pos.relative(d);
				if (seen.contains(next)) continue;
				if (connects(level, pos, state, d, next)) {
					seen.add(next);
					queue.add(next);
				}
			}
		}
		return network;
	}

	/** Whether energy flows from pos to its neighbour on side d. */
	private static boolean connects(ServerLevel level, BlockPos pos, BlockState state, Direction d, BlockPos next) {
		if (!level.isLoaded(next)) return false;
		BlockEntity here = level.getBlockEntity(pos);
		if (here instanceof EnergyNode node && !node.connectsEnergy(d)) return false;
		BlockState other = level.getBlockState(next);
		if (other.getBlock() instanceof CableBlock) return true;
		return level.getBlockEntity(next) instanceof EnergyNode n && n.connectsEnergy(d.getOpposite());
	}

	private static void tick(ServerLevel level) {
		Grid grid = GRIDS.get(level);
		if (grid == null) return;
		for (Network network : List.copyOf(grid.networks)) distribute(level, network);
	}

	private static void distribute(ServerLevel level, Network network) {
		List<EnergyNode> gens = new ArrayList<>(), users = new ArrayList<>(), stores = new ArrayList<>();
		for (BlockPos p : network.nodes) {
			if (!(level.getBlockEntity(p) instanceof EnergyNode node)) continue;
			switch (node.energyRole()) {
				case GENERATOR -> gens.add(node);
				case CONSUMER -> users.add(node);
				case STORAGE -> stores.add(node);
			}
		}
		long gen = 0, demand = 0, storeOut = 0, storeIn = 0, stored = 0, capacity = 0;
		for (EnergyNode n : gens) gen += Math.min(n.energyStored(), n.maxEnergyOutput());
		for (EnergyNode n : users) demand += Math.max(0, Math.min(n.energyCapacity() - n.energyStored(), n.maxEnergyInput()));
		for (EnergyNode n : stores) {
			storeOut += Math.min(n.energyStored(), n.maxEnergyOutput());
			storeIn += Math.max(0, Math.min(n.energyCapacity() - n.energyStored(), n.maxEnergyInput()));
			stored += n.energyStored();
			capacity += n.energyCapacity();
		}
		long limit = network.ratingPerTick;
		long toUsers = Math.min(demand, Math.min(gen + storeOut, limit));
		long fromGen = Math.min(gen, toUsers);
		long fromStore = toUsers - fromGen;
		long toStore = Math.min(Math.min(gen - fromGen, storeIn), Math.max(0, limit - toUsers));
		long genTaken = fromGen + toStore;

		if (toUsers > 0) {
			for (EnergyNode n : users) {
				long need = Math.max(0, Math.min(n.energyCapacity() - n.energyStored(), n.maxEnergyInput()));
				n.setEnergyStored(n.energyStored() + need * toUsers / Math.max(1, demand));
			}
		}
		if (genTaken > 0) {
			for (EnergyNode n : gens) {
				long avail = Math.min(n.energyStored(), n.maxEnergyOutput());
				n.setEnergyStored(n.energyStored() - avail * genTaken / Math.max(1, gen));
			}
		}
		if (fromStore > 0) {
			for (EnergyNode n : stores) {
				long avail = Math.min(n.energyStored(), n.maxEnergyOutput());
				n.setEnergyStored(n.energyStored() - avail * fromStore / Math.max(1, storeOut));
			}
		} else if (toStore > 0) {
			for (EnergyNode n : stores) {
				long room = Math.max(0, Math.min(n.energyCapacity() - n.energyStored(), n.maxEnergyInput()));
				n.setEnergyStored(n.energyStored() + room * toStore / Math.max(1, storeIn));
			}
		}
		network.lastGeneration = gen;
		network.lastDemand = demand;
		network.lastDelivered = toUsers;
		network.lastStored = stored;
		network.lastCapacity = capacity;
	}

	/** The cached network at a position, if any (no building). */
	public static @Nullable Network peek(Level level, BlockPos pos) {
		Grid grid = GRIDS.get(level);
		return grid == null ? null : grid.byPos.get(pos);
	}
}
