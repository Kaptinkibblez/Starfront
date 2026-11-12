package com.starwake;

import com.starwake.network.ModNetworking;
import com.starwake.planet.PlanetCommands;
import com.starwake.planet.PlanetGravity;
import com.starwake.planet.PlanetTravel;
import com.starwake.planet.Planets;
import com.starwake.registry.ModBlockEntities;
import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModEntities;
import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipInteraction;
import com.starwake.ship.SpaceTravel;
import com.starwake.ship.data.ShipPhysics;
import com.starwake.ship.ShipCommands;
import com.starwake.ship.data.ShipFuels;
import com.starwake.ship.data.ShipStats;
import com.starwake.registry.ModCreativeTabs;
import com.starwake.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint for Starwake. It runs on both the dedicated server and the client.
 *
 * This class only wires systems together: every system exposes a static {@code init()}
 * that registers its content. Keep gameplay logic out of here.
 */
public final class StarwakeMod implements ModInitializer {
	/** The mod id. Permanent: changing it would orphan every saved block and item. */
	public static final String MOD_ID = "starwake";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Order matters: items reference blocks, and creative tabs reference both.
		com.starwake.registry.ModComponents.init();
		ModBlocks.init();
		com.starwake.registry.ModPlanetBlocks.init();
		com.starwake.registry.ModHubBlocks.init();
		com.starwake.registry.ModMachines.init();
		com.starwake.registry.ModProcess.init();
		com.starwake.worldgen.CraterFeature.init();
		com.starwake.registry.ModSounds.init();
		ModBlockEntities.init();
		ModEntities.init();
		ModItems.init();
		com.starwake.chem.ElementItems.init();
		com.starwake.metal.MetalItems.init();
		ModCreativeTabs.init();

		// Planets: data-driven registry, gravity, travel, and commands.
		Planets.init();
		com.starwake.planet.proc.ProceduralPlanets.init();
		com.starwake.galaxy.StarSystems.init();
		com.starwake.galaxy.GalaxyCommands.init();
		net.fabricmc.fabric.api.event.registry.DynamicRegistries.registerSynced(com.starwake.trade.Faction.REGISTRY_KEY, com.starwake.trade.Faction.CODEC);
		com.starwake.metal.Metallurgy.init();
		com.starwake.machine.MachineSpec.init();
		com.starwake.machine.PowerNetworks.init();
		com.starwake.logistics.FluidNetworks.init();
		com.starwake.process.Reactions.init();
		com.starwake.process.GasHazards.init();
		com.starwake.planet.Atmospheres.init();
		com.starwake.process.Radiation.init();
		com.starwake.metal.MetalCommands.init();
		com.starwake.trade.TradeData.init();
		com.starwake.trade.Economy.init();
		com.starwake.trade.TradeHubs.init();
		com.starwake.trade.HubCommands.init();
		com.starwake.trade.Contracts.init();
		PlanetGravity.init();
		com.starwake.planet.PlanetHazards.init();
		PlanetTravel.init();
		PlanetCommands.init();

		// Ships: data-driven stats, console networking.
		ShipStats.init();
		ShipFuels.init();
		ShipPhysics.init();
		com.starwake.ship.data.JumpTuning.init();
		com.starwake.ship.data.ShipBlueprints.init();
		ShipCollisions.init();
		ShipInteraction.init();
		SpaceTravel.init();
		ModNetworking.init();
		ShipCommands.init();

		LOGGER.info("Starwake initialized: {} blocks, {} items registered",
				ModBlocks.count(), ModItems.count());
	}

	/** Builds a namespaced id in the {@code starwake} namespace, e.g. {@code starwake:hull_plating}. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
