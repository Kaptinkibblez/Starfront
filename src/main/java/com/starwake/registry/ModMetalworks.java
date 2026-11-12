package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.MeltFurnaceBlockEntity;
import com.starwake.metal.MetalForm;
import com.starwake.metal.SinterPressBlockEntity;
import java.util.List;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * Metalworking content (M8d): melting furnaces, the sintering press, casting moulds.
 */
public final class ModMetalworks {
	public static final Block INDUCTION_FURNACE = ModBlocks.register("induction_furnace",
			p -> new MachineBlock(MeltFurnaceBlockEntity::new, () -> ModMetalworks.MELT_FURNACE_BE, p), machine(12));
	public static final Block VACUUM_ARC_FURNACE = ModBlocks.register("vacuum_arc_furnace",
			p -> new MachineBlock(MeltFurnaceBlockEntity::new, () -> ModMetalworks.MELT_FURNACE_BE, p), machine(13));
	public static final Block SINTER_PRESS = ModBlocks.register("sinter_press",
			p -> new MachineBlock(SinterPressBlockEntity::new, () -> ModMetalworks.SINTER_PRESS_BE, p), machine(9));

	public static final net.minecraft.world.level.block.entity.BlockEntityType<MeltFurnaceBlockEntity> MELT_FURNACE_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, StarwakeMod.id("melt_furnace"),
			FabricBlockEntityTypeBuilder.create(MeltFurnaceBlockEntity::new, INDUCTION_FURNACE, VACUUM_ARC_FURNACE).build());
	public static final net.minecraft.world.level.block.entity.BlockEntityType<SinterPressBlockEntity> SINTER_PRESS_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, StarwakeMod.id("sinter_press"),
			FabricBlockEntityTypeBuilder.create(SinterPressBlockEntity::new, SINTER_PRESS).build());

	public static final MenuType<MachineMenu> INDUCTION_MENU = menu("induction_furnace", MeltFurnaceBlockEntity.LAYOUT);
	public static final MenuType<MachineMenu> VACUUM_ARC_MENU = menu("vacuum_arc_furnace", MeltFurnaceBlockEntity.LAYOUT);
	public static final MenuType<MachineMenu> SINTER_PRESS_MENU = menu("sinter_press", SinterPressBlockEntity.LAYOUT);

	public static final Item BILLET_MOULD = item("billet_mould");
	public static final Item SLAB_MOULD = item("slab_mould");
	public static final Item CASTING_MOULD = item("casting_mould");

	private ModMetalworks() {}

	public static void init() {
		FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), MELT_FURNACE_BE);
	}

	public static List<Block> blocks() {
		return List.of(INDUCTION_FURNACE, VACUUM_ARC_FURNACE, SINTER_PRESS);
	}

	public static List<Item> moulds() {
		return List.of(BILLET_MOULD, SLAB_MOULD, CASTING_MOULD);
	}

	/** The casting a mould makes, or null. */
	public static @Nullable MetalForm mouldForm(ItemStack stack) {
		if (stack.is(BILLET_MOULD)) return MetalForm.BILLET;
		if (stack.is(SLAB_MOULD)) return MetalForm.SLAB;
		if (stack.is(CASTING_MOULD)) return MetalForm.CASTING;
		return null;
	}

	private static BlockBehaviour.Properties machine(int light) {
		return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
				.lightLevel(s -> s.getValue(MachineBlock.LIT) ? light : 0);
	}

	private static MenuType<MachineMenu> menu(String name, MachineMenu.Layout layout) {
		MenuType<MachineMenu>[] holder = new MenuType[1];
		holder[0] = Registry.register(BuiltInRegistries.MENU, StarwakeMod.id(name),
				new MenuType<>((id, inv) -> new MachineMenu(holder[0], layout, id, inv), FeatureFlags.VANILLA_SET));
		return holder[0];
	}

	private static Item item(String name) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
	}
}
