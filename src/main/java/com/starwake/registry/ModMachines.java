package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.logistics.ConveyorBlock;
import com.starwake.logistics.FluidPipeBlock;
import com.starwake.logistics.FluidPipeBlockEntity;
import com.starwake.logistics.FluidTankBlock;
import com.starwake.logistics.FluidTankBlockEntity;
import com.starwake.logistics.WaterSourceBlockEntity;
import com.starwake.machine.CableBlock;
import com.starwake.machine.CombustionGeneratorBlockEntity;
import com.starwake.machine.ElectricFurnaceBlockEntity;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineMenu;
import com.starwake.machine.PowerBlock;
import com.starwake.machine.PowerBlockEntity;
import java.util.List;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Power and logistics content (M8b): generators, storage, consumers, cables, pipes, tanks and
 * conveyors, their block entities and menus. Numbers live in {@code starwake/machine/*.json}.
 */
public final class ModMachines {
	public static final Block COMBUSTION_GENERATOR = ModBlocks.register("combustion_generator",
			p -> new MachineBlock(CombustionGeneratorBlockEntity::new, () -> ModMachines.COMBUSTION_GENERATOR_BE, p),
			machine().lightLevel(s -> s.getValue(MachineBlock.LIT) ? 13 : 0));
	public static final Block ELECTRIC_FURNACE = ModBlocks.register("electric_furnace",
			p -> new MachineBlock(ElectricFurnaceBlockEntity::new, () -> ModMachines.ELECTRIC_FURNACE_BE, p),
			machine().lightLevel(s -> s.getValue(MachineBlock.LIT) ? 10 : 0));
	public static final Block SOLAR_PANEL = ModBlocks.register("solar_panel", p -> new PowerBlock(true, p), machine().noOcclusion());
	public static final Block BATTERY = ModBlocks.register("battery", p -> new PowerBlock(false, p), machine());
	public static final Block COPPER_CABLE = ModBlocks.register("copper_cable", p -> new CableBlock(4, p), cable());
	public static final Block ALUMINIUM_CABLE = ModBlocks.register("aluminium_cable", p -> new CableBlock(6, p), cable());
	public static final Block FLUID_PIPE = ModBlocks.register("fluid_pipe", FluidPipeBlock::new, cable());
	public static final Block FLUID_TANK = ModBlocks.register("fluid_tank", FluidTankBlock::new, machine().noOcclusion());
	public static final Block WATER_PUMP = ModBlocks.register("water_pump",
			p -> new MachineBlock(WaterSourceBlockEntity::new, () -> ModMachines.WATER_SOURCE_BE, p), machine());
	public static final Block ATMOSPHERIC_CONDENSER = ModBlocks.register("atmospheric_condenser",
			p -> new MachineBlock(WaterSourceBlockEntity::new, () -> ModMachines.WATER_SOURCE_BE, p), machine());
	public static final Block CONVEYOR = ModBlocks.register("conveyor", ConveyorBlock::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5f, 4.0f).sound(SoundType.METAL).noOcclusion());

	public static final BlockEntityType<CombustionGeneratorBlockEntity> COMBUSTION_GENERATOR_BE =
			be("combustion_generator", FabricBlockEntityTypeBuilder.create(CombustionGeneratorBlockEntity::new, COMBUSTION_GENERATOR).build());
	public static final BlockEntityType<ElectricFurnaceBlockEntity> ELECTRIC_FURNACE_BE =
			be("electric_furnace", FabricBlockEntityTypeBuilder.create(ElectricFurnaceBlockEntity::new, ELECTRIC_FURNACE).build());
	public static final BlockEntityType<PowerBlockEntity> SOLAR_PANEL_BE =
			be("solar_panel", FabricBlockEntityTypeBuilder.create(PowerBlockEntity::new, SOLAR_PANEL).build());
	public static final BlockEntityType<PowerBlockEntity> BATTERY_BE =
			be("battery", FabricBlockEntityTypeBuilder.create(PowerBlockEntity::new, BATTERY).build());
	public static final BlockEntityType<FluidPipeBlockEntity> FLUID_PIPE_BE =
			be("fluid_pipe", FabricBlockEntityTypeBuilder.create(FluidPipeBlockEntity::new, FLUID_PIPE).build());
	public static final BlockEntityType<FluidTankBlockEntity> FLUID_TANK_BE =
			be("fluid_tank", FabricBlockEntityTypeBuilder.create(FluidTankBlockEntity::new, FLUID_TANK).build());

	public static final BlockEntityType<WaterSourceBlockEntity> WATER_SOURCE_BE =
			be("water_source", FabricBlockEntityTypeBuilder.create(WaterSourceBlockEntity::new, WATER_PUMP, ATMOSPHERIC_CONDENSER).build());

	public static final MenuType<MachineMenu> WATER_PUMP_MENU = menu("water_pump",
			(id, inv) -> new MachineMenu(ModMachines.WATER_PUMP_MENU, WaterSourceBlockEntity.LAYOUT, id, inv));
	public static final MenuType<MachineMenu> CONDENSER_MENU = menu("atmospheric_condenser",
			(id, inv) -> new MachineMenu(ModMachines.CONDENSER_MENU, WaterSourceBlockEntity.LAYOUT, id, inv));
	public static final MenuType<MachineMenu> COMBUSTION_GENERATOR_MENU = menu("combustion_generator",
			(id, inv) -> new MachineMenu(ModMachines.COMBUSTION_GENERATOR_MENU, CombustionGeneratorBlockEntity.LAYOUT, id, inv));
	public static final MenuType<MachineMenu> ELECTRIC_FURNACE_MENU = menu("electric_furnace",
			(id, inv) -> new MachineMenu(ModMachines.ELECTRIC_FURNACE_MENU, ElectricFurnaceBlockEntity.LAYOUT, id, inv));

	private ModMachines() {}

	public static void init() {
		FluidStorage.SIDED.registerForBlockEntity((tank, side) -> tank.storage(), FLUID_TANK_BE);
		FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), WATER_SOURCE_BE);
	}

	public static List<Block> all() {
		return List.of(COMBUSTION_GENERATOR, ELECTRIC_FURNACE, SOLAR_PANEL, BATTERY, COPPER_CABLE, ALUMINIUM_CABLE, FLUID_PIPE, FLUID_TANK, CONVEYOR, WATER_PUMP,
				ATMOSPHERIC_CONDENSER);
	}

	private static BlockBehaviour.Properties machine() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops();
	}

	private static BlockBehaviour.Properties cable() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(0.8f, 3.0f).sound(SoundType.COPPER).noOcclusion();
	}

	private static <T extends BlockEntity> BlockEntityType<T> be(String name, BlockEntityType<T> type) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarwakeMod.id(name), type);
	}

	private static MenuType<MachineMenu> menu(String name, MenuType.MenuSupplier<MachineMenu> supplier) {
		return Registry.register(BuiltInRegistries.MENU, StarwakeMod.id(name), new MenuType<>(supplier, FeatureFlags.VANILLA_SET));
	}
}
