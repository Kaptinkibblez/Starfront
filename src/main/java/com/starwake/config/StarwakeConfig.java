package com.starwake.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.starwake.StarwakeMod;
import com.starwake.network.ConfigSyncPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Server config (M9): {@code config/starwake-server.json}, one flat list of settings in four groups (hazards, ships,
 * travel, economy). Read when the server starts, rewritten with every key (so new settings appear with their
 * defaults), and changed live by operators with {@code /starwake config [key] [value]}. Values clients need (the orbit
 * speed, which they use to place planets) are sent in {@link ConfigSyncPayload} on join and on change. Code reads
 * values through the typed accessors below; each setting's default reproduces the behaviour before the config existed.
 */
public final class StarwakeConfig {
	/** One setting: a number (clamped) or a switch. */
	public record Setting(String key, String group, double fallback, double min, double max, boolean bool, String help) {}

	private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();
	private static final Map<String, Double> VALUES = new LinkedHashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Hazards
	public static final String HAZARDS = flag("hazards.enabled", "hazards", true, "Planet hazards at all (air, toxicity, temperature)");
	public static final String HYPOXIA = number("hazards.hypoxia_damage", "hazards", 1, 0, 10, "Damage multiplier without air");
	public static final String TOXICITY = number("hazards.toxic_damage", "hazards", 1, 0, 10, "Damage multiplier in toxic air");
	public static final String TEMPERATURE = number("hazards.temperature_damage", "hazards", 1, 0, 10, "Damage multiplier from heat and cold");
	public static final String RADIATION = number("hazards.radiation", "hazards", 1, 0, 10, "Radiation dose multiplier (0 = off)");
	public static final String GAS_LEAKS = flag("hazards.gas_leaks", "hazards", true, "Toxic gas clouds from vented machines and tanks");
	public static final String OXYGEN = number("hazards.suit_oxygen", "hazards", 1, 0.1, 100, "Survey Suit oxygen duration multiplier");
	// Ships
	public static final String MAX_BLOCKS = number("ships.max_blocks", "ships", 262_144, 64, 262_144, "Largest ship in blocks");
	public static final String IMPACT = flag("ships.impact_damage", "ships", true, "Hard impacts break hull blocks and hurt the crew");
	public static final String LOCKING = flag("ships.locking", "ships", true, "Owners may lock ships against strangers");
	public static final String IDLE_BURN = number("ships.idle_burn", "ships", 1, 0, 10, "Fuel burnt by unpiloted hovering ships (0 = free)");
	public static final String FUEL_USE = number("ships.fuel_use", "ships", 1, 0, 10, "Propellant burn multiplier for flight");
	// Travel and time
	public static final String QUANTUM_SPEED = number("travel.quantum_speed", "travel", 1, 0.1, 100, "Quantum cruise speed multiplier");
	public static final String JUMP_TIME = number("travel.jump_time", "travel", 1, 0.05, 10, "Jump tunnel duration multiplier");
	public static final String ORBIT_SPEED = number("travel.orbit_speed", "travel", 1, 0, 100,
			"Planet and moon orbit speed multiplier (0 = frozen; changing it moves the planets)");
	// Economy
	public static final String START_CREDITS = number("economy.starting_credits", "economy", 1000, 0, 1e9, "Credits for a new player");
	public static final String BUY_PRICES = number("economy.buy_prices", "economy", 1, 0.01, 100, "Multiplier on what players pay");
	public static final String SELL_PRICES = number("economy.sell_prices", "economy", 1, 0.01, 100, "Multiplier on what players are paid");
	public static final String CONTRACT_REWARDS = number("economy.contract_rewards", "economy", 1, 0, 100, "Contract reward multiplier");
	public static final String REP_GAIN = number("economy.reputation_gain", "economy", 1, 0, 100, "Reputation gain multiplier");
	public static final String REP_LOSS = number("economy.reputation_loss", "economy", 1, 0, 100, "Reputation loss multiplier");

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("starwake-server.json");

	private StarwakeConfig() {}

	private static String number(String key, String group, double fallback, double min, double max, String help) {
		SETTINGS.put(key, new Setting(key, group, fallback, min, max, false, help));
		VALUES.put(key, fallback);
		return key;
	}

	private static String flag(String key, String group, boolean fallback, String help) {
		SETTINGS.put(key, new Setting(key, group, fallback ? 1 : 0, 0, 1, true, help));
		VALUES.put(key, fallback ? 1.0 : 0.0);
		return key;
	}

	public static double get(String key) {
		return VALUES.getOrDefault(key, SETTINGS.containsKey(key) ? SETTINGS.get(key).fallback() : 0);
	}

	public static boolean on(String key) {
		return get(key) >= 0.5;
	}

	public static int maxShipBlocks() {
		return (int) get(MAX_BLOCKS);
	}

	/** Clients: set from the server's sync. */
	public static void applySync(double orbitSpeed) {
		VALUES.put(ORBIT_SPEED, orbitSpeed);
	}

	// --- File ------------------------------------------------------------------------------------------

	public static void load() {
		SETTINGS.values().forEach(s -> VALUES.put(s.key(), s.fallback()));
		if (Files.exists(FILE)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
				for (Setting s : SETTINGS.values()) {
					String[] path = s.key().split("\\.", 2);
					JsonObject group = root.has(path[0]) && root.get(path[0]).isJsonObject() ? root.getAsJsonObject(path[0]) : null;
					JsonElement e = group == null ? null : group.get(path[1]);
					if (e == null || !e.isJsonPrimitive()) continue;
					double v = s.bool() ? (e.getAsJsonPrimitive().isBoolean() ? (e.getAsBoolean() ? 1 : 0) : e.getAsDouble()) : e.getAsDouble();
					VALUES.put(s.key(), Math.clamp(v, s.min(), s.max()));
				}
			} catch (IOException | RuntimeException e) {
				StarwakeMod.LOGGER.error("Couldn't read {}: {} (using defaults)", FILE, e.getMessage());
			}
		}
		save();
	}

	public static void save() {
		JsonObject root = new JsonObject();
		for (Setting s : SETTINGS.values()) {
			String[] path = s.key().split("\\.", 2);
			JsonObject group = root.has(path[0]) ? root.getAsJsonObject(path[0]) : new JsonObject();
			root.add(path[0], group);
			if (s.bool()) group.addProperty(path[1], on(s.key()));
			else group.addProperty(path[1], get(s.key()));
		}
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(root));
		} catch (IOException e) {
			StarwakeMod.LOGGER.error("Couldn't write {}: {}", FILE, e.getMessage());
		}
	}

	// --- Wiring ----------------------------------------------------------------------------------------

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> load());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerPlayNetworking.send(handler.player, sync()));
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("starwake")
				.then(Commands.literal("config").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(StarwakeConfig::list)
						.then(Commands.argument("key", StringArgumentType.string())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(SETTINGS.keySet(), b))
								.executes(c -> show(c, StringArgumentType.getString(c, "key")))
								.then(Commands.argument("value", StringArgumentType.word())
										.executes(c -> set(c, StringArgumentType.getString(c, "key"), StringArgumentType.getString(c, "value"))))))));
	}

	private static ConfigSyncPayload sync() {
		return new ConfigSyncPayload(get(ORBIT_SPEED));
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		String group = "";
		for (Setting s : SETTINGS.values()) {
			if (!s.group().equals(group)) {
				group = s.group();
				String g = group;
				c.getSource().sendSuccess(() -> Component.literal("[" + g + "]"), false);
			}
			c.getSource().sendSuccess(() -> Component.literal("  " + s.key() + " = " + text(s)), false);
		}
		return SETTINGS.size();
	}

	private static int show(CommandContext<CommandSourceStack> c, String key) {
		Setting s = SETTINGS.get(key);
		if (s == null) {
			c.getSource().sendFailure(Component.translatable("commands.starwake.config.unknown", key));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(s.key() + " = " + text(s) + "  (" + s.help()
				+ (s.bool() ? "" : ", " + fmt(s.min()) + ".." + fmt(s.max())) + ", default " + (s.bool() ? s.fallback() >= 0.5 : fmt(s.fallback())) + ")"), false);
		return 1;
	}

	private static int set(CommandContext<CommandSourceStack> c, String key, String raw) {
		Setting s = SETTINGS.get(key);
		if (s == null) {
			c.getSource().sendFailure(Component.translatable("commands.starwake.config.unknown", key));
			return 0;
		}
		double v;
		try {
			v = s.bool() ? (raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("on") ? 1
					: raw.equalsIgnoreCase("false") || raw.equals("0") || raw.equalsIgnoreCase("off") ? 0 : Double.NaN) : Double.parseDouble(raw);
		} catch (NumberFormatException e) {
			v = Double.NaN;
		}
		if (Double.isNaN(v)) {
			c.getSource().sendFailure(Component.translatable("commands.starwake.config.bad_value", raw, key));
			return 0;
		}
		VALUES.put(key, Math.clamp(v, s.min(), s.max()));
		save();
		MinecraftServer server = c.getSource().getServer();
		server.getPlayerList().getPlayers().forEach(p -> ServerPlayNetworking.send(p, sync()));
		c.getSource().sendSuccess(() -> Component.literal(key + " = " + text(s)), true);
		return 1;
	}

	private static String text(Setting s) {
		return s.bool() ? String.valueOf(on(s.key())) : fmt(get(s.key()));
	}

	private static String fmt(double v) {
		return v == Math.rint(v) && Math.abs(v) < 1e12 ? String.valueOf((long) v) : String.valueOf(v);
	}
}
