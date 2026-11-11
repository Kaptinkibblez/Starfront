package com.starwake.registry;

import com.starwake.StarwakeMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/**
 * Starwake's sound events (3j). The audio is original and procedural: see
 * {@code tools/synth_sounds.py}, which writes {@code assets/starwake/sounds/ship/*.ogg}.
 */
public final class ModSounds {
	public static final SoundEvent ENGINE_LOOP = register("ship.engine_loop");
	public static final SoundEvent THRUSTER_LOOP = register("ship.thruster_loop");
	public static final SoundEvent RCS_PUFF = register("ship.rcs_puff");
	public static final SoundEvent QUANTUM_SPOOL = register("ship.quantum_spool");
	public static final SoundEvent QUANTUM_ENGAGE = register("ship.quantum_engage");
	public static final SoundEvent QUANTUM_TRAVEL_LOOP = register("ship.quantum_travel_loop");
	public static final SoundEvent QUANTUM_EXIT = register("ship.quantum_exit");
	public static final SoundEvent ALARM = register("ship.alarm");
	public static final SoundEvent GEAR = register("ship.gear");
	public static final SoundEvent REENTRY_LOOP = register("ship.reentry_loop");

	private ModSounds() {}

	private static SoundEvent register(String name) {
		var id = StarwakeMod.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	/** Forces class loading (and so registration) during mod init. */
	public static void init() {}
}
