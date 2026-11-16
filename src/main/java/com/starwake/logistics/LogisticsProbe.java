package com.starwake.logistics;

import com.starwake.machine.CableBlock;
import com.starwake.machine.EnergyNode;
import com.starwake.machine.PowerNetworks;
import com.starwake.network.LogisticsInfoPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Cable and pipe readouts (M9): what a player looking at a cable or pipe sees under the crosshair. Cables: the grid's
 * generation, demand, delivered power, storage and the weakest cable's rating. Pipes: what flowed through the network
 * over the last second, per fluid, plus its size.
 */
public final class LogisticsProbe {
	private LogisticsProbe() {}

	public static void answer(ServerPlayer player, BlockPos pos) {
		if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 12 * 12 || !player.level().isLoaded(pos)) return;
		var level = player.level();
		var state = level.getBlockState(pos);
		List<Component> lines = new ArrayList<>();
		if (state.getBlock() instanceof CableBlock) {
			PowerNetworks.Network n = PowerNetworks.networkOf(level, pos);
			lines.add(Component.translatable("probe.starwake.grid", kw(EnergyNode.kw(n.lastGeneration)), kw(EnergyNode.kw(n.lastDemand)),
					kw(EnergyNode.kw(n.lastDelivered))));
			if (n.lastCapacity > 0) {
				lines.add(Component.translatable("probe.starwake.grid_storage", String.format("%.2f", n.lastStored / 3.6e6),
						String.format("%.2f", n.lastCapacity / 3.6e6)));
			}
			double rating = n.ratingKw();
			lines.add(Component.translatable("probe.starwake.grid_size", n.cableCount(), n.nodeCount(),
					Double.isInfinite(rating) ? "-" : kw(rating)));
		} else if (state.getBlock() instanceof FluidPipeBlock) {
			var flow = FluidNetworks.flow(level, pos);
			if (flow.flows().isEmpty()) lines.add(Component.translatable("probe.starwake.pipe_idle"));
			flow.flows().forEach((name, kgPerSecond) -> lines.add(Component.translatable("probe.starwake.pipe_flow",
					String.format(kgPerSecond >= 1 ? "%.1f" : "%.3f", kgPerSecond), name)));
			lines.add(Component.translatable("probe.starwake.pipe_size", flow.pipes(), flow.endpoints()));
		} else {
			return;
		}
		ServerPlayNetworking.send(player, new LogisticsInfoPayload(pos, lines));
	}

	private static String kw(double kw) {
		return kw >= 1000 ? String.format("%.2f MW", kw / 1000) : String.format("%.1f kW", kw);
	}
}
