package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModCreativeTabs;
import com.starwake.registry.ModItems;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/** Generates the English (en_us) translations. */
public final class ModLanguageProvider extends FabricLanguageProvider {
	public ModLanguageProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, "en_us", registries);
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
		builder.add(ModCreativeTabs.MAIN_TITLE_KEY, "Starwake");
		builder.add(ModBlocks.HULL_PLATING, "Hull Plating");
		builder.add(ModBlocks.REGOLITH, "Regolith");
		builder.add(ModBlocks.RUST_SAND, "Rust Sand");
		builder.add(ModBlocks.FERRITE_ORE, "Ferrite Ore");
		builder.add(ModItems.RAW_FERRITE, "Raw Ferrite");
		builder.add(ModBlocks.HELM, "Helm");
		builder.add(ModBlocks.THRUSTER, "Thruster");
		builder.add(ModBlocks.FUEL_TANK, "Fuel Tank");

		// Fuel tank messages
		builder.add("message.starwake.fuel_tank.filled", "Loaded %s fuel (%s / %s)");
		builder.add("message.starwake.fuel_tank.full", "Tank full (%s / %s)");
		builder.add("message.starwake.fuel_tank.gauge", "Fuel: %s / %s");

		// Ship console
		builder.add("screen.starwake.ship_console", "Ship Console: Pre-flight Check");
		builder.add("screen.starwake.ship_status", "Ship Console: Status");
		builder.add("screen.starwake.ship_console.rescan", "Re-scan");
		builder.add("screen.starwake.ship_console.assemble", "Assemble Ship");
		builder.add("screen.starwake.ship_console.disassemble", "Disassemble");
		builder.add("message.starwake.assembled", "Ship assembled (%s blocks). WASD to fly, Space up, Sprint down, Shift to leave the helm, R for the console");
		builder.add("message.starwake.disassembled", "Ship disassembled into blocks");
		builder.add("message.starwake.disassemble.failed", "Can't disassemble this ship");
		builder.add("message.starwake.disassemble.moving", "Bring the ship to a stop before disassembling");
		builder.add("message.starwake.disassemble.obstructed", "Can't disassemble: blocked at %s %s %s");
		builder.add("message.starwake.helm_taken", "Someone is already at the helm");
		builder.add("message.starwake.ship_block_locked", "Ship blocks can't be used while assembled yet (coming in Milestone 4)");
		builder.add("hud.starwake.ship", "Fuel %s / %s   Speed %s m/s   Alt %s");
		builder.add("console.starwake.ship.mass", "%s blocks, mass %s t, gravity here %sg");
		builder.add("console.starwake.ship.fuel", "Fuel %s / %s (refuel: right-click a fuel tank on board with fuel)");
		builder.add("console.starwake.ship.controls", "WASD fly, Space up, Sprint down, Shift leave the helm. Engines hover automatically");
		builder.add("console.starwake.ship.can_disassemble", "Stationary: you can disassemble the ship back into blocks to edit it");
		builder.add("console.starwake.ship.moving", "Moving: stop to disassemble");
		builder.add("key.starwake.ship_console", "Ship Console");
		builder.add("key.category.starwake.starwake", "Starwake");
		builder.add("entity.starwake.ship", "Ship");
		builder.add("preflight.starwake.structure", "Structure: %s blocks, mass %s t");
		builder.add("preflight.starwake.too_big", "Structure exceeds %s blocks. Is the ship touching another build?");
		builder.add("preflight.starwake.multiple_helms", "%s Helms found: a ship can only have one");
		builder.add("preflight.starwake.no_pilot_room", "The Helm needs 2 free blocks above it for the pilot");
		builder.add("preflight.starwake.no_thrusters", "No thrusters");
		builder.add("preflight.starwake.lift_low", "Not enough lift: %s vs weight %s at %sg (need %s). Add downward thrusters or lose mass");
		builder.add("preflight.starwake.lift_ok", "Lift %s vs weight %s at %sg (%sx)");
		builder.add("preflight.starwake.blocked_exhaust", "%s thruster(s) have blocked exhaust and won't fire");
		builder.add("preflight.starwake.handling", "Thrust: forward %s, reverse %s, left %s, right %s, down %s");
		builder.add("preflight.starwake.cant_move", "No thrust for: %s");
		builder.add("preflight.starwake.no_tanks", "No fuel tanks");
		builder.add("preflight.starwake.no_fuel", "Fuel tanks are empty (capacity %s). Load fuel into them");
		builder.add("preflight.starwake.fuel_ok", "Fuel %s / %s (about %ss at full burn)");
		builder.add("preflight.starwake.sealed", "Cabin sealed (%s blocks of air): space-capable");
		builder.add("preflight.starwake.not_sealed", "Cabin not sealed: air escapes near %s %s %s. Atmosphere-only");
		builder.add("preflight.starwake.verdict_space", "Ready for flight, including space");
		builder.add("preflight.starwake.verdict_atmo", "Ready for atmospheric flight (seal the cabin to reach space)");
		builder.add("preflight.starwake.verdict_grounded", "Grounded: fix the failed checks above");

		// Planets (names referenced from data/starwake/starwake/planet/*.json)
		builder.add("planet.starwake.veyra", "Veyra");

		// Commands
		builder.add("commands.starwake.planet.unknown", "Unknown planet: %s");
		builder.add("commands.starwake.ship.not_helm", "That block isn't a Helm");
		builder.add("commands.starwake.ship.none_nearby", "No ship within 64 blocks");
		builder.add("commands.starwake.ship.drift", "Ship %s is drifting");
		builder.add("commands.starwake.ship.assembled", "Assembled ship %s at %s");
		builder.add("commands.starwake.planet.missing_dimension", "Planet %s has no loaded dimension");
		builder.add("commands.starwake.planet.list.header", "%s planet(s) charted:");
		builder.add("commands.starwake.planet.list.entry", " - %s (%s), gravity %sg");
		builder.add("commands.starwake.planet.tp.success", "Sent %s player(s) to %s");
		builder.add("commands.starwake.planet.leave.success", "Returned %s player(s) home");
	}
}
