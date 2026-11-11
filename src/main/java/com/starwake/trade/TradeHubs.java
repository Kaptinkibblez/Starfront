package com.starwake.trade;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.starwake.StarwakeMod;
import com.starwake.galaxy.Galaxy;
import com.starwake.galaxy.SolarSystem;
import com.starwake.galaxy.StarSystems;
import com.starwake.network.HubListPayload;
import com.starwake.planet.Planet;
import com.starwake.planet.PlanetRef;
import com.starwake.planet.Planets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Trade hubs (M7): orbital stations and surface outposts, planned deterministically for every
 * star system and built from templates the first time a player comes near.
 *
 * <p><b>Planning</b> ({@link #plan}): Solace always gets a Solace Accord port off the Overworld
 * and outposts on up to three planets (Veyra's mining camp among them). Elsewhere about half the
 * systems with planets have a station, near one of the planets, and about a third of the planets
 * have an outpost. The faction comes from the factions' hub rules (outposts only settle the
 * archetypes a rule lists), weighted. Stations sit 3 km outside a planet's atmosphere on the
 * sunward side; outposts 250-700 blocks from the planet's origin (where you land from the
 * planet list).
 *
 * <p><b>Building</b>: within 700 (station) or 160 (outpost) blocks of a player, the template is
 * placed (outposts at the surface height, the site cleared), guards and crew spawn, and the
 * hub's origin is saved ({@link State}). Pressurized boxes become breathable zones
 * ({@link HubZones}), and players in the system get the hub list ({@link HubListPayload}).
 */
public final class TradeHubs {
	/** A planned hub. {@code anchor} is the template's min corner (outposts: y found at build). */
	public record Hub(String id, String system, String faction, String kind, String economy, String template,
			ResourceKey<Level> dimension, BlockPos anchor, String name, String planet) {
		public boolean station() {
			return kind.equals("station");
		}
	}

	private static final Map<String, List<Hub>> PLANS = new HashMap<>();
	private static int plansFor = -1;

	private TradeHubs() {}

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PLANS.clear();
			HubZones.clear(false);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(TradeHubs::refreshZones);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 40 == 0) tickBuild(server);
			if (server.getTickCount() % 1200 == 600) HubNpcs.upkeep(server);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.getPlayer()));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> sync(player));
	}

	// ---------------------------------------------------------------------------------------
	// Planning
	// ---------------------------------------------------------------------------------------

	/** Every hub of a system (cached; deterministic from the world seed). */
	public static synchronized List<Hub> plan(MinecraftServer server, String system) {
		if (plansFor != TradeData.generation()) {
			PLANS.clear();
			plansFor = TradeData.generation();
		}
		return PLANS.computeIfAbsent(system, s -> makePlan(server, s));
	}

	public static Optional<Hub> hub(MinecraftServer server, String id) {
		int slash = id.lastIndexOf('/');
		if (slash < 0) return Optional.empty();
		return plan(server, id.substring(0, slash)).stream().filter(h -> h.id().equals(id)).findFirst();
	}

	/** Planets (with bodies) of a system, whether or not it's open. */
	private static List<PlanetRef> planetsOf(MinecraftServer server, String system) {
		List<PlanetRef> out = new ArrayList<>();
		if (system.equals(Galaxy.HOME)) {
			for (PlanetRef ref : Planets.all(server.registryAccess())) {
				if (ref.planet().system().equals(Galaxy.HOME) && ref.planet().body().isPresent()) out.add(ref);
			}
		} else {
			Galaxy.star(system).ifPresent(star -> {
				var plan = StarSystems.plan(server.registryAccess(), star);
				out.addAll(plan.stars());
				plan.planets().forEach(g -> out.add(g.ref()));
			});
		}
		out.sort(Comparator.comparing(r -> r.key().identifier().toString()));
		return out;
	}

	private static List<Hub> makePlan(MinecraftServer server, String system) {
		List<Hub> hubs = new ArrayList<>();
		var factions = server.registryAccess().lookupOrThrow(Faction.REGISTRY_KEY).listElements()
				.sorted(Comparator.comparing(h -> h.key().identifier().toString())).toList();
		if (factions.isEmpty()) return hubs;
		long seed = RandomSupport.mixStafford13(server.getWorldGenSettings().options().seed() ^ system.hashCode() ^ 0x48554253L);
		RandomSource r = RandomSource.create(seed);
		List<PlanetRef> bodies = planetsOf(server, system);
		Vec3 star = bodies.stream().filter(p -> p.planet().body().get().star()).findFirst()
				.map(p -> p.planet().body().get().position()).orElse(Vec3.ZERO);
		List<PlanetRef> planets = bodies.stream().filter(p -> p.planet().destination() && !p.planet().body().get().star()).toList();
		boolean home = system.equals(Galaxy.HOME);

		// Station.
		if (!planets.isEmpty() && (home || r.nextDouble() < 0.5)) {
			PlanetRef host = home ? planets.stream().filter(p -> p.planet().dimension().equals(Level.OVERWORLD)).findFirst().orElse(planets.getFirst())
					: planets.get(r.nextInt(planets.size()));
			Pick pick = home ? find(factions, "solace_accord", "station") : pickRule(r, factions, "station", null);
			if (pick != null) {
				var t = TradeData.template(pick.rule().template()).orElse(null);
				if (t != null) {
					var body = host.planet().body().get();
					Vec3 toStar = star.subtract(body.position());
					Vec3 dir = toStar.lengthSqr() < 1 ? new Vec3(1, 0, 0) : new Vec3(toStar.x, 0, toStar.z).normalize();
					Vec3 centre = body.position().add(dir.scale(body.entryRadius() + 3000));
					BlockPos anchor = BlockPos.containing(centre.x - t.sizeX() / 2.0, -t.sizeY() / 2.0, centre.z - t.sizeZ() / 2.0);
					String name = host.planet().name().getString() + " " + economyName(pick.rule().economy());
					hubs.add(new Hub(system + "/station", system, pick.faction(), "station", pick.rule().economy(), pick.rule().template(),
							SolarSystem.spaceOf(system), anchor, name, host.planet().name().getString()));
				}
			}
		}

		// Outposts.
		List<PlanetRef> candidates = new ArrayList<>(planets.stream().filter(p -> !p.planet().dimension().equals(Level.OVERWORLD)).toList());
		int wanted = home ? Math.min(3, candidates.size()) : (int) Math.round(candidates.size() / 3.0 + r.nextDouble() * 0.5);
		if (home) {
			// Veyra always has its mining camp.
			candidates.sort(Comparator.comparing(p -> p.key().identifier().getPath().equals("veyra") ? 0 : 1));
		} else {
			java.util.Collections.shuffle(candidates, new java.util.Random(seed));
		}
		int n = 0;
		for (PlanetRef planet : candidates) {
			if (n >= wanted) break;
			String archetype = archetypeOf(planet.planet());
			Pick pick = pickRule(r, factions, "outpost", archetype);
			if (pick == null) continue;
			var t = TradeData.template(pick.rule().template()).orElse(null);
			if (t == null) continue;
			double angle = r.nextDouble() * Math.PI * 2, radius = 250 + r.nextDouble() * 450;
			int cx = (int) (Math.cos(angle) * radius), cz = (int) (Math.sin(angle) * radius);
			BlockPos anchor = new BlockPos(cx - t.sizeX() / 2, 64, cz - t.sizeZ() / 2);
			n++;
			String name = planet.planet().name().getString() + " " + economyName(pick.rule().economy());
			hubs.add(new Hub(system + "/o" + n, system, pick.faction(), "outpost", pick.rule().economy(), pick.rule().template(),
					planet.planet().dimension(), anchor, name, planet.planet().name().getString()));
		}
		return List.copyOf(hubs);
	}

	private record Pick(String faction, Faction.HubRule rule) {}

	private static @Nullable Pick find(List<Holder.Reference<Faction>> factions, String faction, String kind) {
		for (var f : factions) {
			if (!f.key().identifier().getPath().equals(faction)) continue;
			for (var rule : f.value().hubs()) if (rule.kind().equals(kind)) return new Pick(f.key().identifier().getPath(), rule);
		}
		return null;
	}

	private static @Nullable Pick pickRule(RandomSource r, List<Holder.Reference<Faction>> factions, String kind, @Nullable String archetype) {
		List<Pick> options = new ArrayList<>();
		double total = 0;
		for (var f : factions) {
			for (var rule : f.value().hubs()) {
				if (!rule.kind().equals(kind)) continue;
				if (archetype != null && !rule.archetypes().isEmpty() && !rule.archetypes().contains(archetype)) continue;
				options.add(new Pick(f.key().identifier().getPath(), rule));
				total += rule.weight();
			}
		}
		double roll = r.nextDouble() * total;
		for (Pick p : options) {
			roll -= p.rule().weight();
			if (roll <= 0) return p;
		}
		return options.isEmpty() ? null : options.getLast();
	}

	/** The archetype id of a planet ("lush"...), from its type name; hand-made Veyra counts as a desert. */
	public static String archetypeOf(Planet planet) {
		if (planet.archetype().isPresent() && planet.archetype().get().getContents() instanceof TranslatableContents t) {
			String key = t.getKey();
			return key.substring(key.lastIndexOf('.') + 1);
		}
		return planet.dimension().identifier().getPath().equals("veyra") ? "desert" : "lush";
	}

	private static String economyName(String economy) {
		return TradeData.economy(economy).map(e -> e.name().getString()).orElse("Hub");
	}

	// ---------------------------------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------------------------------

	private static void tickBuild(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.level();
			String system = SolarSystem.systemOf(level);
			for (Hub hub : plan(server, system)) {
				if (!hub.dimension().equals(level.dimension()) || origin(server, hub) != null) continue;
				var t = TradeData.template(hub.template()).orElse(null);
				if (t == null) continue;
				double reach = hub.station() ? 700 : 160;
				double dx = player.getX() - (hub.anchor().getX() + t.sizeX() / 2.0), dz = player.getZ() - (hub.anchor().getZ() + t.sizeZ() / 2.0);
				if (dx * dx + dz * dz > reach * reach) continue;
				build(level, hub, t);
			}
		}
	}

	/** Places a hub (idempotent per hub: the origin is saved). */
	public static void build(ServerLevel level, Hub hub, TradeData.Template t) {
		BlockPos origin = hub.anchor();
		if (!hub.station()) {
			int cx = hub.anchor().getX() + t.sizeX() / 2, cz = hub.anchor().getZ() + t.sizeZ() / 2;
			level.getChunk(cx >> 4, cz >> 4); // generate the site
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
			surface = Math.max(surface, level.getMinY() + 8);
			origin = new BlockPos(hub.anchor().getX(), surface - t.ground(), hub.anchor().getZ());
		}
		place(level, hub.template(), t, origin);
		State state = state(level.getServer());
		state.origins.put(hub.id(), origin);
		state.setDirty();
		HubNpcs.spawnAll(level, hub, t, origin);
		refreshZones(level.getServer());
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (SolarSystem.systemOf(p.level()).equals(hub.system())) sync(p);
		}
		StarwakeMod.LOGGER.info("Built hub {} '{}' ({}) at {}", hub.id(), hub.name(), hub.faction(), origin);
	}

	/**
	 * Places a template's blocks at an origin ({@code ' '} keeps the world, {@code '.'} clears), then
	 * runs a shape-update pass over everything placed so panes, bars, walls and stairs connect (the
	 * first pass sets each block without looking at neighbours placed after it).
	 */
	public static void place(ServerLevel level, String id, TradeData.Template t, BlockPos origin) {
		HolderLookup<Block> blocks = level.holderLookup(Registries.BLOCK);
		Map<Character, BlockState> palette = new HashMap<>();
		for (var e : t.palette().entrySet()) {
			if (e.getKey().length() != 1) continue;
			try {
				palette.put(e.getKey().charAt(0), BlockStateParser.parseForBlock(blocks, e.getValue(), false).blockState());
			} catch (CommandSyntaxException ex) {
				StarwakeMod.LOGGER.warn("Hub template {}: bad block '{}'", id, e.getValue());
			}
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		List<BlockPos> placed = new ArrayList<>();
		for (int y = 0; y < t.layers().size(); y++) {
			List<String> rows = t.layers().get(y);
			for (int z = 0; z < rows.size(); z++) {
				String row = rows.get(z);
				for (int x = 0; x < row.length(); x++) {
					char c = row.charAt(x);
					if (c == ' ') continue;
					BlockState state = c == '.' ? air : palette.get(c);
					if (state == null) continue;
					BlockPos pos = origin.offset(x, y, z);
					level.setBlock(pos, state, Block.UPDATE_CLIENTS);
					if (c != '.') placed.add(pos);
				}
			}
		}
		for (BlockPos pos : placed) {
			BlockState state = level.getBlockState(pos);
			BlockState shaped = Block.updateFromNeighbourShapes(state, level, pos);
			if (shaped != state && !shaped.isAir()) level.setBlock(pos, shaped, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	/** Forgets where a hub was built (admin rebuild). */
	public static void forget(MinecraftServer server, Hub hub) {
		State state = state(server);
		state.origins.remove(hub.id());
		state.setDirty();
	}

	/** Where a hub was built, or null if not yet. */
	public static @Nullable BlockPos origin(MinecraftServer server, Hub hub) {
		return state(server).origins.get(hub.id());
	}

	/** The built hub whose layout contains a position, if any. */
	public static Optional<Hub> hubAt(ServerLevel level, BlockPos pos) {
		MinecraftServer server = level.getServer();
		for (Hub hub : plan(server, SolarSystem.systemOf(level))) {
			if (!hub.dimension().equals(level.dimension())) continue;
			BlockPos o = origin(server, hub);
			var t = TradeData.template(hub.template()).orElse(null);
			if (o == null || t == null) continue;
			if (pos.getX() >= o.getX() - 2 && pos.getY() >= o.getY() - 2 && pos.getZ() >= o.getZ() - 2 && pos.getX() <= o.getX() + t.sizeX() + 1
					&& pos.getY() <= o.getY() + t.sizeY() + 1 && pos.getZ() <= o.getZ() + t.sizeZ() + 1) {
				return Optional.of(hub);
			}
		}
		return Optional.empty();
	}

	/** Pressurized boxes of a built hub, in world coordinates. */
	public static List<AABB> zones(Hub hub, TradeData.Template t, BlockPos origin) {
		List<AABB> out = new ArrayList<>();
		for (List<Integer> b : t.pressurized()) {
			if (b.size() != 6) continue;
			out.add(new AABB(origin.getX() + b.get(0), origin.getY() + b.get(1), origin.getZ() + b.get(2),
					origin.getX() + b.get(3) + 1, origin.getY() + b.get(4) + 1, origin.getZ() + b.get(5) + 1));
		}
		return out;
	}

	private static void refreshZones(MinecraftServer server) {
		Map<ResourceKey<Level>, List<AABB>> zones = new HashMap<>();
		State state = state(server);
		for (var e : state.origins.entrySet()) {
			hub(server, e.getKey()).ifPresent(hub -> TradeData.template(hub.template()).ifPresent(t ->
					zones.computeIfAbsent(hub.dimension(), k -> new ArrayList<>()).addAll(zones(hub, t, e.getValue()))));
		}
		HubZones.set(false, zones);
	}

	// ---------------------------------------------------------------------------------------
	// Sync
	// ---------------------------------------------------------------------------------------

	/** Sends the hubs of the player's current system. */
	public static void sync(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		String system = SolarSystem.systemOf(player.level());
		List<HubListPayload.HubView> views = new ArrayList<>();
		for (Hub hub : plan(server, system)) {
			var t = TradeData.template(hub.template()).orElse(null);
			if (t == null) continue;
			BlockPos o = origin(server, hub);
			BlockPos base = o == null ? hub.anchor() : o;
			Vec3 beacon = new Vec3(base.getX() + t.beacon().get(0) + 0.5, base.getY() + t.beacon().get(1), base.getZ() + t.beacon().get(2) + 0.5);
			views.add(new HubListPayload.HubView(hub.id(), hub.name(), hub.faction(), hub.kind(), hub.dimension(), beacon, hub.planet(),
					o == null ? List.of() : zones(hub, t, o)));
		}
		ServerPlayNetworking.send(player, new HubListPayload(views));
	}

	// ---------------------------------------------------------------------------------------

	/** Where each built hub was placed. */
	public static final class State extends SavedData {
		final Map<String, BlockPos> origins = new HashMap<>();
		static final Codec<State> CODEC = Codec.unboundedMap(Codec.STRING, BlockPos.CODEC).xmap(map -> {
			State s = new State();
			s.origins.putAll(map);
			return s;
		}, s -> s.origins);
		static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("hubs"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	}

	private static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}

	public static Component factionName(MinecraftServer server, String faction) {
		return server.registryAccess().lookupOrThrow(Faction.REGISTRY_KEY).get(StarwakeMod.id(faction))
				.map(h -> h.value().name()).orElse(Component.literal(faction));
	}
}
