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
		builder.add(ModBlocks.SEAT, "Seat");
		builder.add(ModBlocks.POWER_PLANT, "Power Plant");
		builder.add(ModBlocks.COOLER, "Cooler");
		builder.add(ModBlocks.SHIELD_GENERATOR, "Shield Generator");
		builder.add(ModBlocks.QUANTUM_DRIVE, "Quantum Drive");
		// Pre-flight: components
		builder.add("preflight.starwake.no_power", "No power plant: nothing to run the engines");
		builder.add("preflight.starwake.power", "Power: %s plant(s), %s output");
		builder.add("preflight.starwake.cooling", "Cooling %s vs heat %s: plants at full output");
		builder.add("preflight.starwake.overheating", "Overheating: heat %s vs cooling %s, plants throttled to %s. Add coolers");
		builder.add("preflight.starwake.engines_underpowered", "Engines want %s power but get %s at the default split: thrust %s");
		builder.add("preflight.starwake.no_shields", "No shield generator: crashes hurt the crew");
		builder.add("preflight.starwake.shields", "Shields %s HP (regen %s at the default split)");
		builder.add("preflight.starwake.no_quantum", "No quantum drive: can't travel between planets");
		builder.add("preflight.starwake.quantum", "Quantum drive: spools in %ss at the default split");
		// Quantum travel
		builder.add("message.starwake.quantum.no_drive", "No quantum drive aboard");
		builder.add("message.starwake.quantum.no_power", "Route power to Quantum to spool the drive");
		builder.add("message.starwake.quantum.aborted", "Quantum travel aborted");
		// Power routing
		builder.add("screen.starwake.power.engines", "Engines: %s%%");
		builder.add("screen.starwake.power.shields", "Shields: %s%%");
		builder.add("screen.starwake.power.quantum", "Quantum: %s%%");
		builder.add("console.starwake.ship.power", "Power %s, cooling %s, engines %s, shield %s");
		builder.add("hud.starwake.qt.short", "QUANTUM TRAVEL");
		builder.add("hud.starwake.qt.mode", "QT > %s");
		builder.add("hud.starwake.qt.travel", "QUANTUM > %s");
		builder.add("hud.starwake.qt.committed", "Committed: no steering");
		builder.add("hud.starwake.qt.no_target", "No target: aim at a marker");
		builder.add("hud.starwake.qt.too_close", "Too close: fly there");
		builder.add("hud.starwake.qt.obstructed", "Route obstructed");
		builder.add("hud.starwake.qt.no_fuel", "Not enough fuel");
		builder.add("hud.starwake.qt.spooling", "Spooling...");
		builder.add("hud.starwake.qt.align", "Align nose with marker");
		builder.add("hud.starwake.qt.ready", "READY: hold B");
		builder.add("key.starwake.quantum", "Quantum Drive (tap: mode, hold: engage)");
		builder.add("star.starwake.solace", "Solace");
		builder.add("message.starwake.quantum.on", "Quantum mode: aim at a marker, then hold B");
		builder.add("message.starwake.quantum.off", "Quantum mode off");
		builder.add("message.starwake.quantum.course", "Course set: line up on the marker and hold B");
		builder.add("message.starwake.quantum.no_target", "No quantum target: put the nose on a marker");
		builder.add("message.starwake.quantum.not_spooled", "Quantum drive still spooling");
		builder.add("message.starwake.quantum.too_close", "Too close to calibrate a jump");
		builder.add("message.starwake.quantum.obstructed", "Quantum route obstructed");
		builder.add("message.starwake.quantum.no_fuel", "Not enough fuel for this jump");
		builder.add("message.starwake.quantum.not_aligned", "Not aligned with the target");
		builder.add("message.starwake.quantum.engaged", "Quantum travel to %s: ETA %s");
		builder.add("message.starwake.quantum.arrived", "Dropped out of quantum near %s. Fly into the atmosphere to land");
		builder.add("console.starwake.ship.near", "Nearest: %s. Fly into a planet's atmosphere to land");
		builder.add("console.starwake.ship.route", "%s: %s, %s, fuel %s %s");
		builder.add("console.starwake.ship.route.too_close", "(here)");
		builder.add("console.starwake.ship.route.obstructed", "(obstructed)");
		builder.add("console.starwake.ship.route.no_fuel", "(low fuel)");
		builder.add("key.starwake.roll_left", "Roll Left");
		builder.add("key.starwake.roll_right", "Roll Right");
		builder.add("key.starwake.free_look", "Free Look (hold)");
		builder.add("key.starwake.leave_seat", "Leave Seat");
		builder.add("hud.starwake.ship6", "Fuel %s/%s  %s m/s  HDG %s°  PIT %s°  ROL %s°  %s");
		builder.add("hud.starwake.ship.alt", "ALT %s");
		builder.add("hud.starwake.ship.orbit", "Orbiting %s");
		builder.add("message.starwake.disassemble.not_level", "Level the ship (within 20 degrees) to disassemble");
		builder.add("message.starwake.disassemble.obstructed_rotation", "Not enough room to square the ship up for disassembly");

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
		builder.add("message.starwake.assembled", "Ship assembled (%s blocks). Steer with the mouse, WASD/Space/Ctrl thrust, Z/C roll, Shift boost, Alt free look, X leave, R console");
		builder.add("message.starwake.disassembled", "Ship disassembled into blocks");
		builder.add("message.starwake.disassemble.failed", "Can't disassemble this ship");
		builder.add("message.starwake.disassemble.moving", "Bring the ship to a stop before disassembling");
		builder.add("message.starwake.disassemble.obstructed", "Can't disassemble: blocked at %s %s %s");
		builder.add("message.starwake.helm_taken", "Someone is already at the helm");
		builder.add("message.starwake.ship_block_locked", "Ship blocks can't be used while assembled yet (coming in Milestone 4)");
		builder.add("hud.starwake.ship", "Fuel %s / %s   Speed %s m/s   Alt %s");
		builder.add("hud.starwake.ship.space", "Fuel %s / %s   Speed %s m/s   Orbiting %s");
		builder.add("message.starwake.not_sealed_for_space", "Hull not sealed: this ship can't leave the atmosphere");
		builder.add("message.starwake.entered_space", "In orbit (%s). Fly back into the atmosphere to land, or press B for quantum mode");
		builder.add("message.starwake.entered_atmosphere", "Entering the atmosphere of %s");
		builder.add("message.starwake.course.not_in_space", "Courses can only be set from space");
		builder.add("message.starwake.course.unknown", "Unknown destination");
		builder.add("screen.starwake.ship_console.course", "QT: %s");
		builder.add("console.starwake.ship.sealed", "Cabin sealed: space-capable");
		builder.add("console.starwake.ship.unsealed", "Cabin not sealed: atmosphere only");
		builder.add("console.starwake.ship.to_space", "Climb above Y %s to reach space");
		builder.add("console.starwake.ship.mass", "%s blocks, %s t, %sg");
		builder.add("console.starwake.ship.fuel", "Fuel %s / %s");
		builder.add("console.starwake.ship.controls", "Mouse steers. WASD/Space/Ctrl thrust, Z/C roll, Shift boost, Alt free look, X leave seat. Engines hover automatically");
		builder.add("console.starwake.ship.can_disassemble", "Stationary: you can disassemble the ship back into blocks to edit it");
		builder.add("console.starwake.ship.moving", "Moving: stop to disassemble");
		builder.add("console.starwake.ship.space_no_disassemble", "Land on a planet to disassemble");
		builder.add("message.starwake.lost_in_space", "You drifted out of orbit and were brought home");
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
		builder.add("planet.starwake.overworld", "Overworld");
		builder.add("planet.starwake.space", "Deep Space");

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
