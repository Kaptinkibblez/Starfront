package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineMenu;
import com.starwake.nuclear.AcceleratorBlockEntity;
import com.starwake.nuclear.FissionReactorBlockEntity;
import com.starwake.nuclear.FusionReactorBlockEntity;
import com.starwake.nuclear.GasCentrifugeBlockEntity;
import java.util.List;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** Nuclear content (M8g): gas centrifuge, fission and fusion reactors, particle accelerator. */
public final class ModNuclear {
	public static final Block GAS_CENTRIFUGE = ModBlocks.register("gas_centrifuge",
			p -> new MachineBlock(GasCentrifugeBlockEntity::new, () -> ModNuclear.GAS_CENTRIFUGE_BE, p), machine(4));
	public static final Block FISSION_REACTOR = ModBlocks.register("fission_reactor",
			p -> new MachineBlock(FissionReactorBlockEntity::new, () -> ModNuclear.FISSION_REACTOR_BE, p), machine(12));
	public static final Block FUSION_REACTOR = ModBlocks.register("fusion_reactor",
			p -> new MachineBlock(FusionReactorBlockEntity::new, () -> ModNuclear.FUSION_REACTOR_BE, p), machine(15));
	public static final Block PARTICLE_ACCELERATOR = ModBlocks.register("particle_accelerator",
			p -> new MachineBlock(AcceleratorBlockEntity::new, () -> ModNuclear.ACCELERATOR_BE, p), machine(10));

	public static final BlockEntityType<GasCentrifugeBlockEntity> GAS_CENTRIFUGE_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("gas_centrifuge"), FabricBlockEntityTypeBuilder.create(GasCentrifugeBlockEntity::new, GAS_CENTRIFUGE).build());
	public static final BlockEntityType<FissionReactorBlockEntity> FISSION_REACTOR_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("fission_reactor"), FabricBlockEntityTypeBuilder.create(FissionReactorBlockEntity::new, FISSION_REACTOR).build());
	public static final BlockEntityType<FusionReactorBlockEntity> FUSION_REACTOR_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("fusion_reactor"), FabricBlockEntityTypeBuilder.create(FusionReactorBlockEntity::new, FUSION_REACTOR).build());
	public static final BlockEntityType<AcceleratorBlockEntity> ACCELERATOR_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			StarwakeMod.id("particle_accelerator"), FabricBlockEntityTypeBuilder.create(AcceleratorBlockEntity::new, PARTICLE_ACCELERATOR).build());

	public static final MenuType<MachineMenu> GAS_CENTRIFUGE_MENU = menu("gas_centrifuge", GasCentrifugeBlockEntity.LAYOUT);
	public static final MenuType<MachineMenu> FISSION_REACTOR_MENU = menu("fission_reactor", FissionReactorBlockEntity.LAYOUT);
	public static final MenuType<MachineMenu> FUSION_REACTOR_MENU = menu("fusion_reactor", FusionReactorBlockEntity.LAYOUT);
	public static final MenuType<MachineMenu> ACCELERATOR_MENU = menu("particle_accelerator", AcceleratorBlockEntity.LAYOUT);

	private ModNuclear() {}

	public static void init() {
		FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), FISSION_REACTOR_BE);
		FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), FUSION_REACTOR_BE);
	}

	public static List<Block> blocks() {
		return List.of(GAS_CENTRIFUGE, FISSION_REACTOR, FUSION_REACTOR, PARTICLE_ACCELERATOR);
	}

	private static BlockBehaviour.Properties machine(int light) {
		return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5f, 12.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
				.lightLevel(s -> s.getValue(MachineBlock.LIT) ? light : 0);
	}

	private static MenuType<MachineMenu> menu(String name, MachineMenu.Layout layout) {
		@SuppressWarnings("unchecked")
		MenuType<MachineMenu>[] holder = new MenuType[1];
		holder[0] = Registry.register(BuiltInRegistries.MENU, StarwakeMod.id(name),
				new MenuType<>((id, inv) -> new MachineMenu(holder[0], layout, id, inv), FeatureFlags.VANILLA_SET));
		return holder[0];
	}
}
