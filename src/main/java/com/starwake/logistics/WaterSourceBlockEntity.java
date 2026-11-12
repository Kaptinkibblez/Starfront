package com.starwake.logistics;

import com.starwake.galaxy.SolarSystem;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.planet.Hazards;
import com.starwake.planet.Planets;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Chemicals;
import com.starwake.registry.ModMachines;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Process water (M8c-2). The <b>Water Pump</b> draws {@code rate} kg/s from a water source block
 * beside or below it (an intake, so the source is not used up). The <b>Atmospheric Condenser</b>
 * wrings water out of breathable air (no air, no water: on airless worlds and in space, ship it
 * in or melt ice); {@code power / rate} is its real energy cost, about 1.5 MJ per kg.
 * Both fill a small tank that pipes can pull from, and push into neighbouring fluid storage.
 */
public class WaterSourceBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(List.of(), new int[] {}, new int[] {}, new int[] {}, null,
			new int[] {-1, -1, 80});
	private final boolean pump;
	private final ChemicalTank tank;

	public WaterSourceBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.WATER_SOURCE_BE, pos, state);
		this.pump = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("water_pump");
		this.tank = new ChemicalTank(8, false, true, s -> s.equals("H2O"), this::setChanged);
	}

	public Storage<FluidVariant> fluidStorage() {
		return tank;
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return pump ? ModMachines.WATER_PUMP_MENU : ModMachines.CONDENSER_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	private boolean hasSource(ServerLevel level) {
		for (Direction d : Direction.values()) {
			if (d == Direction.UP) continue;
			FluidState fluid = level.getFluidState(worldPosition.relative(d));
			if (fluid.is(FluidTags.WATER) && fluid.isSource()) return true;
		}
		return false;
	}

	private boolean humidAir(ServerLevel level) {
		Hazards hazards = Planets.forDimension(level.registryAccess(), level.dimension()).map(p -> p.planet().hazards()).orElse(Hazards.EARTHLIKE);
		return hazards.breathable() && hazards.temperature() > 0 && !SolarSystem.isSpace(level.dimension());
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		boolean source = pump ? hasSource(level) : humidAir(level);
		if (source && tank.total() < tank.capacityKg() * Chemicals.KG && drawPower(spec().power())) {
			tank.add("H2O", spec().rate() / 20.0);
			working = true;
		}
		progressMax = 100;
		progress = (int) Math.round(100 * tank.totalKg() / tank.capacityKg());
		if ((level.getGameTime() + worldPosition.asLong()) % 10 == 0 && tank.total() > 0) push(level);
		if (getBlockState().getValue(MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
		}
	}

	/** Pushes water into neighbouring tanks and machines. */
	private void push(ServerLevel level) {
		for (Direction d : Direction.values()) {
			Storage<FluidVariant> target = FluidStorage.SIDED.find(level, worldPosition.relative(d), d.getOpposite());
			if (target == null || target == tank) continue;
			StorageUtil.move(tank, target, v -> true, Long.MAX_VALUE, null);
		}
	}

	@Override
	protected void writeData(int[] v) {
		int base = MachineMenu.TANKS + 6;
		v[base] = (int) Math.round(tank.totalKg() * 100);
		v[base + 1] = (int) tank.capacityKg();
		v[base + 2] = com.starwake.process.Species.get("H2O").map(com.starwake.process.Species.Info::index).orElse(-1);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		tank.read(input, "water");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		tank.write(output, "water");
	}
}
