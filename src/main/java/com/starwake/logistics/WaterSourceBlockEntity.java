package com.starwake.logistics;

import com.starwake.galaxy.SolarSystem;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.planet.Hazards;
import com.starwake.planet.Planets;
import com.starwake.StarwakeMod;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Species;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
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
 * beside or below it (an intake, so the source is not used up). M8c-3a: what it pumps depends on the
 * biome of that water: seawater ({@code #starwake:seawater}), salt-lake brine ({@code #starwake:salt_lake})
 * or acid lake water ({@code #starwake:acid_water}), with their real dissolved salts; fresh water elsewhere. The <b>Atmospheric Condenser</b>
 * wrings water out of breathable air (no air, no water: on airless worlds and in space, ship it
 * in or melt ice); {@code power / rate} is its real energy cost, about 1.5 MJ per kg.
 * Both fill a small tank that pipes can pull from, and push into neighbouring fluid storage.
 */
public class WaterSourceBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(List.of(), new int[] {}, new int[] {}, new int[] {}, null,
			new int[] {-1, -1, 80});
	private final boolean pump;
	/** M8c-3b: a Gas Well on a gas field produces natural gas instead of water. */
	private final boolean well;
	private final ChemicalTank tank;
	private Map<String, Double> water;

	public WaterSourceBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.WATER_SOURCE_BE, pos, state);
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		this.pump = path.equals("water_pump");
		this.well = path.equals("gas_well");
		this.tank = well ? new ChemicalTank(8, false, true, s -> true, this::setChanged) : new ChemicalTank(8, false, true, s -> true, this::setChanged).mixed();
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
		return well ? ModMachines.GAS_WELL_MENU : pump ? ModMachines.WATER_PUMP_MENU : ModMachines.CONDENSER_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	public static final TagKey<Biome> SEAWATER = TagKey.create(Registries.BIOME, StarwakeMod.id("seawater"));
	public static final TagKey<Biome> SALT_LAKE = TagKey.create(Registries.BIOME, StarwakeMod.id("salt_lake"));
	public static final TagKey<Biome> ACID_WATER = TagKey.create(Registries.BIOME, StarwakeMod.id("acid_water"));
	public static final TagKey<Biome> GAS_FIELD = TagKey.create(Registries.BIOME, StarwakeMod.id("gas_field"));

	/** The water source the pump draws from, or null. */
	private BlockPos source(ServerLevel level) {
		for (Direction d : Direction.values()) {
			if (d == Direction.UP) continue;
			FluidState fluid = level.getFluidState(worldPosition.relative(d));
			if (fluid.is(FluidTags.WATER) && fluid.isSource()) return worldPosition.relative(d);
		}
		return null;
	}

	/** What 1 kg of water from this spot contains (species -> mass fraction). */
	public static Map<String, Double> waterAt(ServerLevel level, BlockPos pos) {
		var biome = level.getBiome(pos);
		String feed = biome.is(SEAWATER) ? "seawater" : biome.is(SALT_LAKE) ? "salt_lake_brine" : biome.is(ACID_WATER) ? "acid_lake_water" : null;
		if (feed == null) return Map.of("H2O", 1.0);
		return Species.feed(StarwakeMod.id(feed)).map(f -> f.assay().masses(1.0)).orElse(Map.of("H2O", 1.0));
	}

	private boolean humidAir(ServerLevel level) {
		Hazards hazards = Planets.forDimension(level.registryAccess(), level.dimension()).map(p -> p.planet().hazards()).orElse(Hazards.EARTHLIKE);
		return hazards.breathable() && hazards.temperature() > 0 && !SolarSystem.isSpace(level.dimension());
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		BlockPos from = pump ? source(level) : null;
		boolean source = well ? level.getBiome(worldPosition).is(GAS_FIELD) && worldPosition.getY() < level.getSeaLevel() + 40
				: pump ? from != null : humidAir(level);
		if (source && tank.total() < tank.capacityKg() * Chemicals.KG && drawPower(spec().power())) {
			if (well) {
				double kg = spec().rate() / 20.0;
				Species.feed(StarwakeMod.id("natural_gas")).ifPresent(f -> f.assay().masses(1.0).forEach((sp, x) -> tank.add(sp, x * kg)));
			} else if (pump) {
				if (water == null || (level.getGameTime() + worldPosition.asLong()) % 100 == 0) water = waterAt(level, from);
				double kg = spec().rate() / 20.0;
				water.forEach((sp, f) -> tank.add(sp, f * kg));
			} else {
				tank.add("H2O", spec().rate() / 20.0);
			}
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
		String main = tank.main();
		v[base + 2] = Species.get(main == null ? "H2O" : main).map(Species.Info::index).orElse(-1);
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
