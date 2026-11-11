package com.starwake.planet.proc;

import com.starwake.StarwakeMod;
import com.starwake.galaxy.Body;
import com.starwake.network.PlanetListPayload;
import com.starwake.planet.Hazards;
import com.starwake.planet.Planet;
import com.starwake.planet.PlanetRef;
import com.starwake.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/**
 * The procedural planets of the home system (M5, decision B). On server start, the world seed
 * lays out 4-6 extra planets around Solace: each orbit picks an archetype (weighted, no repeats
 * until every kind is used, hot kinds near the star and cold ones far out because the archetype
 * says where it orbits), then every value inside the archetype's ranges. Each planet becomes a
 * persistent Fantasy runtime dimension ({@code starwake:solace_<n>}) with its own seed.
 *
 * <p>Everything derives from {@code (world seed, orbit index)}, so the same world always gets
 * the same planets and nothing is stored. Clients get the finished planet list in a
 * {@link PlanetListPayload} when they join (they don't have the seed or the archetype data).
 *
 * <p>Known limit: changing archetype JSON changes the planets of existing worlds; chunks already
 * generated keep their old look.
 */
public final class ProceduralPlanets {
	/** The home system's id, used in planet ids and seeds. M6 seeds other systems. */
	public static final String SYSTEM = "solace";
	/** Keep planets at least this far apart (and from the hand-made ones). */
	private static final double MIN_SPACING = 700_000;

	/** What the seed picked for one planet's terrain (server only). */
	public record Spec(ResourceKey<DimensionType> dimensionType, ResourceKey<NoiseGeneratorSettings> terrain, BlockState stone,
			BlockState fluid, int seaLevel, List<PlanetArchetype.BiomeOption> biomes, long seed) {}

	/** A generated planet: its public definition plus the server-side generator picks. */
	public record Generated(PlanetRef ref, ResourceKey<PlanetArchetype> archetype, Spec spec) {}

	private static List<Generated> generated = List.of();
	private static List<PlanetRef> planets = List.of();
	private static int generation;

	private ProceduralPlanets() {}

	public static void init() {
		DynamicRegistries.register(PlanetArchetype.REGISTRY_KEY, PlanetArchetype.CODEC);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			generate(server);
			Planets.rebuildIndex(server);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(ProceduralPlanets::openLevels);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> set(List.of(), List.of()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				sender.sendPacket(new PlanetListPayload(planets)));
	}

	/** The procedural planets known on this side (server: generated; client: received). */
	public static List<PlanetRef> planets() {
		return planets;
	}

	public static List<Generated> generated() {
		return generated;
	}

	/** Bumps whenever the list changes, so caches ({@code Planets.all}) know to rebuild. */
	public static int generation() {
		return generation;
	}

	/** Client: the list the server sent. */
	public static void setFromServer(List<PlanetRef> list) {
		set(generated, List.copyOf(list));
	}

	private static synchronized void set(List<Generated> gen, List<PlanetRef> list) {
		generated = gen;
		planets = list;
		generation++;
	}

	// ---------------------------------------------------------------------------------------

	private static void generate(MinecraftServer server) {
		RegistryAccess access = server.registryAccess();
		Registry<PlanetArchetype> archetypes = access.lookupOrThrow(PlanetArchetype.REGISTRY_KEY);
		if (archetypes.size() == 0) {
			set(List.of(), List.of());
			return;
		}
		long worldSeed = server.getWorldGenSettings().options().seed();
		RandomSource system = RandomSource.create(RandomSupport.mixStafford13(worldSeed ^ SYSTEM.hashCode()));
		int count = 4 + system.nextInt(3);

		// Positions already taken: every hand-made body.
		List<Vec3> taken = new ArrayList<>();
		Planets.registry(server).forEach(p -> p.body().ifPresent(b -> taken.add(b.position())));

		List<Holder.Reference<PlanetArchetype>> bag = new ArrayList<>();
		List<Generated> result = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			if (bag.isEmpty()) archetypes.listElements().forEach(bag::add);
			Holder.Reference<PlanetArchetype> archetype = pickWeighted(system, bag);
			bag.remove(archetype);
			long seed = RandomSupport.mixStafford13(worldSeed + index * 0x9E3779B97F4A7C15L);
			result.add(create(access, archetype, index, seed, taken));
		}
		set(List.copyOf(result), result.stream().map(Generated::ref).toList());
		for (Generated g : result) {
			Vec3 at = g.ref().planet().body().orElseThrow().position();
			StarwakeMod.LOGGER.info("Planet {} = {} ({}), {} at {} Mm [{} {}] r={}, {}g, {} C{}", g.ref().key().identifier(),
					g.ref().planet().name().getString(), g.archetype().identifier(), g.spec().terrain().identifier(),
					Math.round(g.ref().planet().body().orElseThrow().position().length() / 1e5) / 10.0, Math.round(at.x), Math.round(at.z),
					Math.round(g.ref().planet().body().orElseThrow().entryRadius()),
					String.format("%.2f", g.ref().planet().gravity()), Math.round(g.ref().planet().hazards().temperature()),
					g.ref().planet().hazards().breathable() ? ", breathable" : "");
		}
	}

	private static Holder.Reference<PlanetArchetype> pickWeighted(RandomSource random, List<Holder.Reference<PlanetArchetype>> bag) {
		double total = bag.stream().mapToDouble(a -> a.value().weight()).sum();
		double roll = random.nextDouble() * total;
		for (var a : bag) {
			roll -= a.value().weight();
			if (roll <= 0) return a;
		}
		return bag.getLast();
	}

	private static Generated create(RegistryAccess access, Holder.Reference<PlanetArchetype> holder, int index, long seed, List<Vec3> taken) {
		PlanetArchetype a = holder.value();
		RandomSource r = RandomSource.create(seed);
		Identifier id = StarwakeMod.id(SYSTEM + "_" + index);

		// Orbit: the archetype's distance band, at an angle clear of every other body.
		double distance = a.orbit().pick(r) * 1.0e6;
		Vec3 position = Vec3.ZERO;
		for (int attempt = 0; attempt < 40; attempt++) {
			double angle = r.nextDouble() * Math.PI * 2;
			position = new Vec3(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
			final Vec3 p = position;
			if (taken.stream().allMatch(t -> t.distanceTo(p) > MIN_SPACING)) break;
			distance *= 1.04; // drift outward if this band is crowded
		}
		taken.add(position);

		PlanetArchetype.Environment env = a.environment();
		double radius = a.radius().pick(r);
		double gravity = env.gravity().pick(r);
		double air = Math.max(0, env.airDensity().pick(r));
		boolean breathable = r.nextDouble() < env.breathable();
		float toxicity = (float) Math.max(0, env.toxicity().pick(r));
		// Temperature follows the orbit: the inner edge of the archetype's band is its hottest.
		double band = Math.max(1e-6, a.orbit().max() - a.orbit().min());
		double outward = Math.clamp((distance / 1.0e6 - a.orbit().min()) / band, 0, 1);
		double span = env.temperature().max() - env.temperature().min();
		float temperature = (float) (env.temperature().max() - span * outward + (r.nextDouble() - 0.5) * span * 0.2);
		Hazards hazards = new Hazards(breathable, toxicity, temperature);

		var colors = a.colors();
		Body body = new Body(Optional.empty(), position, radius, air > 0.01 ? radius * (1.08 + 0.12 * Math.min(air, 1.5)) : 0,
				16000, pick(r, colors.surface()), pick(r, colors.secondary()), pick(r, colors.atmosphere()), false);
		String name = PlanetNames.generate(r);
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
		Planet planet = new Planet(Component.literal(name), dimension, gravity, true, Optional.of(body), air, hazards,
				Optional.of(a.name()));

		// Biomes: the required ones plus a seeded handful of the optional ones.
		List<PlanetArchetype.BiomeOption> required = new ArrayList<>(), optional = new ArrayList<>();
		for (var option : a.biomes()) (option.required() ? required : optional).add(option);
		int wanted = Math.max(1, a.biomeCount().pickInt(r));
		List<PlanetArchetype.BiomeOption> biomes = new ArrayList<>(required);
		while (biomes.size() < wanted && !optional.isEmpty()) biomes.add(optional.remove(r.nextInt(optional.size())));
		if (biomes.isEmpty()) biomes.add(a.biomes().getFirst());

		Spec spec = new Spec(pick(r, a.dimensionTypes()), pick(r, a.terrain()), pick(r, a.stone()), pick(r, a.fluid()),
				a.seaLevel().pickInt(r), List.copyOf(biomes), seed);
		return new Generated(new PlanetRef(ResourceKey.create(Planets.REGISTRY_KEY, id), planet), holder.key(), spec);
	}

	private static <T> T pick(RandomSource random, List<T> options) {
		return options.get(random.nextInt(options.size()));
	}

	// ---------------------------------------------------------------------------------------

	/** Opens (or loads) every procedural planet's dimension before players can join. */
	private static void openLevels(MinecraftServer server) {
		Fantasy fantasy = Fantasy.get(server);
		for (Generated g : generated) {
			RuntimeLevelConfig config = new RuntimeLevelConfig()
					.setDimensionType(g.spec().dimensionType())
					.setGenerator(PlanetGenerators.create(server.registryAccess(), g.spec()))
					.setSeed(g.spec().seed())
					.setShouldTickTime(true)
					.setMirrorOverworldGameRules(true)
					.setMirrorOverworldDifficulty(true);
			fantasy.getOrOpenPersistentLevel(g.ref().planet().dimension().identifier(), config);
		}
	}
}
