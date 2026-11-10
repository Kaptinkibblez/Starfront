package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.ship.block.FuelTankBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Registry of Starwake block entity types. */
public final class ModBlockEntities {
	public static final BlockEntityType<FuelTankBlockEntity> FUEL_TANK = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, StarwakeMod.id("fuel_tank"),
			FabricBlockEntityTypeBuilder.create(FuelTankBlockEntity::new, ModBlocks.FUEL_TANK).build());

	private ModBlockEntities() {}

	public static void init() {}
}
