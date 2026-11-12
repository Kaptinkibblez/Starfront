package com.starwake.client.wristcom;

import com.starwake.client.wristcom.apps.ComingSoonApp;
import com.starwake.client.wristcom.apps.HomeApp;
import com.starwake.client.wristcom.apps.ShipApp;
import com.starwake.client.wristcom.apps.StarmapApp;
import java.util.ArrayList;
import java.util.List;

/**
 * The Wristcom's app list. Built-in apps register here; future systems add theirs with
 * {@link #register} (and replace a "coming soon" placeholder by registering under the same title).
 */
public final class WristcomApps {
	private static final List<WristcomApp> APPS = new ArrayList<>();

	private WristcomApps() {}

	public static void init() {
		register(new HomeApp());
		register(new ShipApp());
		register(new com.starwake.client.wristcom.apps.CrewApp());
		register(new StarmapApp());
		register(new com.starwake.client.wristcom.apps.ContractsApp());
		register(new com.starwake.client.wristcom.apps.TradeApp());
		register(new com.starwake.client.wristcom.apps.CodexApp());
		register(new ComingSoonApp("cargo", "Ca", 0xFFFF9F6B, 8));
		register(new ComingSoonApp("comms", "Cm", 0xFFB59CFF, 9));
	}

	/** Adds an app, or replaces one with the same title. */
	public static void register(WristcomApp app) {
		for (int i = 0; i < APPS.size(); i++) {
			if (APPS.get(i).title().getString().equals(app.title().getString())) {
				APPS.set(i, app);
				return;
			}
		}
		APPS.add(app);
	}

	public static List<WristcomApp> all() {
		return List.copyOf(APPS);
	}
}
