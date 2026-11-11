package com.starwake.client.galaxy;

import com.starwake.galaxy.Galaxy;
import com.starwake.galaxy.SolarSystem;
import com.starwake.network.GalaxyInfoPayload;
import com.starwake.network.SystemInfoPayload;
import com.starwake.network.SystemInfoRequestPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client side of the galaxy (M6): configures {@link Galaxy} from the seed the server sends,
 * caches system details ({@link SystemInfoPayload}) the galaxy map asks for, and caches the
 * neighbouring stars drawn in the space sky.
 */
public final class GalaxyClient {
	private static final Map<String, List<SystemInfoPayload.PlanetInfo>> INFO = new HashMap<>();
	private static final Set<String> REQUESTED = new HashSet<>();

	private GalaxyClient() {}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(GalaxyInfoPayload.TYPE, (payload, context) ->
				Galaxy.configure(payload.seed(), context.client().getConnection().registryAccess()));
		ClientPlayNetworking.registerGlobalReceiver(SystemInfoPayload.TYPE, (payload, context) -> INFO.put(payload.system(), payload.planets()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			INFO.clear();
			REQUESTED.clear();
		});
	}

	/** A system's planets, asking the server the first time (empty until the answer arrives). */
	public static Optional<List<SystemInfoPayload.PlanetInfo>> info(String system) {
		List<SystemInfoPayload.PlanetInfo> known = INFO.get(system);
		if (known != null) return Optional.of(known);
		if (REQUESTED.add(system) && Minecraft.getInstance().getConnection() != null) {
			ClientPlayNetworking.send(new SystemInfoRequestPayload(system));
		}
		return Optional.empty();
	}

	/** The star system the player is in (Solace on planets without a system). */
	public static Galaxy.@Nullable Star here() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || !Galaxy.configured()) return null;
		return Galaxy.star(SolarSystem.systemOf(minecraft.level)).orElse(null);
	}

	public static Vec3 herePosition() {
		Galaxy.Star here = here();
		return here == null ? Vec3.ZERO : here.position();
	}
}
