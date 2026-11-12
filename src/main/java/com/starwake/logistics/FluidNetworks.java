package com.starwake.logistics;

import com.starwake.machine.MachineSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fluid pipe networks (M8b): connected pipes, found by a bounded flood fill and cached until a
 * pipe in them changes. A network lists its endpoints: (pipe, side) pairs that touch a fluid
 * storage. Every 10 ticks each extracting side pumps from its storage into all the network's
 * other (non-extracting) endpoints, through Fabric's transfer API, so anything that exposes fluid
 * storage works.
 */
public final class FluidNetworks {
	private static final int MAX_SIZE = 2048, PERIOD = 10;

	record Endpoint(BlockPos pipe, Direction side, boolean extract) {
		BlockPos target() {
			return pipe.relative(side);
		}
	}

	static final class Network {
		final Set<BlockPos> pipes = new HashSet<>();
		final List<Endpoint> endpoints = new ArrayList<>();
	}

	private static final Map<Level, Map<BlockPos, Network>> BY_POS = new WeakHashMap<>();

	private FluidNetworks() {}

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> BY_POS.clear());
	}

	public static void invalidate(Level level, BlockPos pos) {
		if (level.isClientSide()) return;
		Map<BlockPos, Network> map = BY_POS.computeIfAbsent(level, l -> new HashMap<>());
		Set<Network> dead = new HashSet<>();
		Network here = map.get(pos);
		if (here != null) dead.add(here);
		for (Direction d : Direction.values()) {
			Network n = map.get(pos.relative(d));
			if (n != null) dead.add(n);
		}
		for (Network n : dead) n.pipes.forEach(map::remove);
	}

	private static Network network(ServerLevel level, BlockPos start) {
		Map<BlockPos, Network> map = BY_POS.computeIfAbsent(level, l -> new HashMap<>());
		Network cached = map.get(start);
		if (cached != null) return cached;
		Network network = new Network();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start.immutable());
		network.pipes.add(start.immutable());
		while (!queue.isEmpty() && network.pipes.size() < MAX_SIZE) {
			BlockPos pos = queue.poll();
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof FluidPipeBlock)) continue;
			for (Direction d : Direction.values()) {
				PipeSide side = state.getValue(FluidPipeBlock.SIDES.get(d));
				if (side == PipeSide.NONE) continue;
				BlockPos next = pos.relative(d);
				if (!level.isLoaded(next)) continue;
				if (level.getBlockState(next).getBlock() instanceof FluidPipeBlock) {
					if (network.pipes.add(next)) queue.add(next);
				} else {
					network.endpoints.add(new Endpoint(pos, d, side == PipeSide.EXTRACT));
				}
			}
		}
		for (BlockPos p : network.pipes) map.put(p, network);
		return network;
	}

	/** Called from a pipe with an extract side: pump its share every PERIOD ticks. */
	public static void pump(ServerLevel level, BlockPos pos, BlockState state) {
		if ((level.getGameTime() + pos.asLong()) % PERIOD != 0) return;
		Network network = network(level, pos);
		long amount = (long) (MachineSpec.of(state.getBlock()).rate() * FluidConstants.BUCKET * PERIOD / 20.0);
		for (Endpoint source : network.endpoints) {
			if (!source.extract() || !source.pipe().equals(pos)) continue;
			Storage<FluidVariant> from = FluidStorage.SIDED.find(level, source.target(), source.side().getOpposite());
			if (from == null) continue;
			List<Storage<FluidVariant>> targets = new ArrayList<>();
			for (Endpoint e : network.endpoints) {
				if (e.extract() || e.target().equals(source.target())) continue;
				Storage<FluidVariant> to = FluidStorage.SIDED.find(level, e.target(), e.side().getOpposite());
				if (to != null) targets.add(to);
			}
			if (targets.isEmpty()) continue;
			try (Transaction tx = Transaction.openOuter()) {
				long moved = StorageUtil.move(from, new CombinedStorage<>(targets), v -> true, amount, tx);
				if (moved > 0) tx.commit();
			}
		}
	}
}
