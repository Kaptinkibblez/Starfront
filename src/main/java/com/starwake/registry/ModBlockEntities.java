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
			FabricBlockEntityTypeBuilder.create(FuelTankBlockEntity::new, ModShipParts.fuelTanks()).build());

	private ModBlockEntities() {}

	public static void init() {
		// M8f: pipes and Gas Cylinders feed propellant and fusion gases into fuel tanks.
		net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.registerForBlockEntity((be, side) -> be.fluidStorage(), FUEL_TANK);
	}
}
