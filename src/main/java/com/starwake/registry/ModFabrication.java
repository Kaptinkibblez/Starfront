package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.fab.Blueprint;
import com.starwake.fab.ComponentItem;
import com.starwake.fab.FabricatorBlockEntity;
import com.starwake.fab.MaterialBlockEntity;
import com.starwake.fab.SemiconductorBlockEntity;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineMenu;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Fabrication content (M8e-1): the Crystal Puller and Clean Fab (electronics), the Fabricator and its
 * blueprint registry, silicon wafers, microchips, the sub-components, and the material block entity
 * that hull plating (and later Mk parts) use to remember their alloy.
 */
public final class ModFabrication {
	public static final Block CRYSTAL_PULLER = ModBlocks.register("crystal_puller",
			p -> new MachineBlock(SemiconductorBlockEntity::new, () -> ModFabrication.SEMICONDUCTOR_BE, p), machine(10));
	public static final Block CLEAN_FAB = ModBlocks.register("clean_fab",
			p -> new MachineBlock(SemiconductorBlockEntity::new, () -> ModFabrication.SEMICONDUCTOR_BE, p), machine(6));
	public static final Block FABRICATOR = ModBlocks.register("fabricator",
			p -> new MachineBlock(FabricatorBlockEntity::new, () -> ModFabrication.FABRICATOR_BE, p), machine(6));

	public static final BlockEntityType<SemiconductorBlockEntity> SEMICONDUCTOR_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("semiconductor"), FabricBlockEntityTypeBuilder.create(SemiconductorBlockEntity::new, CRYSTAL_PULLER, CLEAN_FAB).build());
	public static final BlockEntityType<FabricatorBlockEntity> FABRICATOR_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("fabricator"), FabricBlockEntityTypeBuilder.create(FabricatorBlockEntity::new, FABRICATOR).build());
	public static final BlockEntityType<MaterialBlockEntity> MATERIAL_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("material"), FabricBlockEntityTypeBuilder.create(MaterialBlockEntity::new, ModBlocks.HULL_PLATING).build());

	public static final MenuType<MachineMenu> CRYSTAL_PULLER_MENU = menu("crystal_puller", SemiconductorBlockEntity.PULLER);
	public static final MenuType<MachineMenu> CLEAN_FAB_MENU = menu("clean_fab", SemiconductorBlockEntity.FAB);
	public static final MenuType<MachineMenu> FABRICATOR_MENU = menu("fabricator", FabricatorBlockEntity.LAYOUT);

	public static final Item SILICON_WAFER = item("silicon_wafer", Item::new, 64);
	public static final Item MICROCHIP = item("microchip", Item::new, 64);

	private static final List<Item> COMPONENTS = new ArrayList<>();
	public static final Item PRESSURE_VESSEL = component("pressure_vessel");
	public static final Item STRUCTURAL_FRAME = component("structural_frame");
	public static final Item COMBUSTION_CHAMBER = component("combustion_chamber");
	public static final Item NOZZLE = component("nozzle");
	public static final Item TURBOPUMP = component("turbopump");
	public static final Item HEAT_EXCHANGER = component("heat_exchanger");
	public static final Item MAGNET_COIL = component("magnet_coil");
	public static final Item CONTROL_BOARD = component("control_board");

	private ModFabrication() {}

	public static void init() {
		DynamicRegistries.registerSynced(Blueprint.REGISTRY_KEY, Blueprint.CODEC);
		FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), SEMICONDUCTOR_BE);
	}

	public static List<Block> blocks() {
		return List.of(CRYSTAL_PULLER, CLEAN_FAB, FABRICATOR);
	}

	public static List<Item> components() {
		return List.copyOf(COMPONENTS);
	}

	private static BlockBehaviour.Properties machine(int light) {
		return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
				.lightLevel(s -> s.getValue(MachineBlock.LIT) ? light : 0);
	}

	private static MenuType<MachineMenu> menu(String name, MachineMenu.Layout layout) {
		@SuppressWarnings("unchecked")
		MenuType<MachineMenu>[] holder = new MenuType[1];
		holder[0] = Registry.register(BuiltInRegistries.MENU, StarwakeMod.id(name),
				new MenuType<>((id, inv) -> new MachineMenu(holder[0], layout, id, inv), FeatureFlags.VANILLA_SET));
		return holder[0];
	}

	private static Item item(String name, java.util.function.Function<Item.Properties, Item> factory, int stack) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key).stacksTo(stack)));
	}

	private static Item component(String name) {
		Item item = item(name, ComponentItem::new, 16);
		COMPONENTS.add(item);
		return item;
	}
}
