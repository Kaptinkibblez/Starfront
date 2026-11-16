package com.starwake.ship;

import com.starwake.network.CargoPayload;
import com.starwake.ship.yard.Shipyard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Ship manifest (M9) for the Wristcom Cargo app: walks the block entities of a ship's plot in the shipyard and sums
 * every container's items (same item + components counted together) and every tank's fluids, plus the ship's fuel and
 * fusion. Only owner and crew (or anyone, on an unlocked ship) may read it, from within 64 blocks.
 */
public final class ShipCargo {
	private ShipCargo() {}

	public static CargoPayload manifest(ServerPlayer player, int shipId) {
		CargoPayload none = new CargoPayload(shipId, -1, List.of(), List.of(), 0, 0, 0, 0);
		if (!(player.level().getEntity(shipId) instanceof ShipEntity ship) || ship.distanceTo(player) > 64
				|| !(ship.canPilot(player) || !ship.isLocked())) return none;
		ServerLevel yard = Shipyard.level(player.level().getServer());
		ShipStructure structure = ship.structure();
		if (yard == null || structure == null || ship.plot() < 0) return none;
		Map<ItemStack, Long> items = new LinkedHashMap<>();
		Map<FluidVariant, Long> fluids = new HashMap<>();
		int containers = 0;
		for (ShipStructure.Entry e : structure.entries()) {
			if (e.blockEntity().isEmpty()) continue;
			BlockPos pos = Shipyard.toPlot(ship.plot(), e.pos());
			if (yard.getBlockEntity(pos) instanceof Container c) {
				containers++;
				for (int i = 0; i < c.getContainerSize(); i++) {
					ItemStack s = c.getItem(i);
					if (s.isEmpty()) continue;
					ItemStack key = items.keySet().stream().filter(k -> ItemStack.isSameItemSameComponents(k, s)).findFirst().orElse(null);
					if (key == null) key = s.copyWithCount(1);
					items.merge(key, (long) s.getCount(), Long::sum);
				}
			}
			var storage = FluidStorage.SIDED.find(yard, pos, null);
			if (storage != null) {
				for (var view : storage) {
					if (!view.isResourceBlank() && view.getAmount() > 0) fluids.merge(view.getResource(), view.getAmount(), Long::sum);
				}
			}
		}
		List<CargoPayload.Line> lines = new ArrayList<>();
		items.forEach((k, n) -> lines.add(new CargoPayload.Line(k, n)));
		lines.sort((a, b) -> Long.compare(b.count(), a.count()));
		List<CargoPayload.Tank> tanks = new ArrayList<>();
		fluids.forEach((v, d) -> tanks.add(new CargoPayload.Tank(FluidVariantAttributes.getName(v), d / (double) FluidConstants.BUCKET)));
		tanks.sort((a, b) -> Double.compare(b.kg(), a.kg()));
		return new CargoPayload(shipId, containers, lines.size() > 256 ? lines.subList(0, 256) : lines, tanks, ship.fuel(), ship.fuelCapacity(),
				ship.fusion(), ship.fusionCapacity());
	}
}
