package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineMenu;
import com.starwake.process.BulkItem;
import com.starwake.process.Chemicals;
import com.starwake.process.ProcessMachineBlockEntity;
import com.starwake.process.ProcessMachineBlockEntity.Process;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * Process chemistry content (M8c-1): the unit-operation machines (one block entity class for all,
 * see {@link Process}), their menus, the bulk material items and the chemical fluid.
 */
public final class ModProcess {
	private static final Map<Process, Block> BLOCKS = new EnumMap<>(Process.class);
	private static final Map<Process, MenuType<MachineMenu>> MENUS = new EnumMap<>(Process.class);
	private static final Map<BulkItem.Kind, BulkItem> BULK = new EnumMap<>(BulkItem.Kind.class);

	static {
		for (Process p : Process.values()) {
			BLOCKS.put(p, ModBlocks.register(p.machine, props -> new MachineBlock(ProcessMachineBlockEntity::new,
					() -> ModProcess.PROCESS_MACHINE_BE, props), BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f)
					.sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> s.getValue(MachineBlock.LIT) ? 9 : 0)));
		}
	}

	public static final BlockEntityType<ProcessMachineBlockEntity> PROCESS_MACHINE_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("process_machine"), FabricBlockEntityTypeBuilder.create(ProcessMachineBlockEntity::new, BLOCKS.values().toArray(Block[]::new)).build());

	static {
		for (Process p : Process.values()) {
			MENUS.put(p, Registry.register(BuiltInRegistries.MENU, StarwakeMod.id(p.machine),
					new MenuType<>((id, inv) -> new MachineMenu(ModProcess.menu(p), p.layout(), id, inv), FeatureFlags.VANILLA_SET)));
		}
		for (BulkItem.Kind kind : BulkItem.Kind.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id("bulk_" + kind.id));
			BULK.put(kind, Registry.register(BuiltInRegistries.ITEM, key, new BulkItem(kind, new Item.Properties().setId(key))));
		}
	}

	public static final com.starwake.process.GasCylinderItem GAS_CYLINDER;

	static {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id("gas_cylinder"));
		GAS_CYLINDER = Registry.register(BuiltInRegistries.ITEM, key, new com.starwake.process.GasCylinderItem(new Item.Properties().setId(key).stacksTo(1)));
	}

	private ModProcess() {}

	public static void init() {
		Chemicals.init();
		FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), PROCESS_MACHINE_BE);
		FluidStorage.ITEM.registerForItems((stack, context) -> new com.starwake.process.GasCylinderItem.Storage(context), GAS_CYLINDER);
	}

	public static Block block(Process p) {
		return BLOCKS.get(p);
	}

	public static List<Block> blocks() {
		return new ArrayList<>(BLOCKS.values());
	}

	public static MenuType<MachineMenu> menu(Process p) {
		return MENUS.get(p);
	}

	public static BulkItem bulk(BulkItem.Kind kind) {
		return BULK.get(kind);
	}

	public static List<BulkItem> bulkItems() {
		return new ArrayList<>(BULK.values());
	}
}
