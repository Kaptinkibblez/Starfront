package com.starwake.process;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Process chemistry data (M8c), server-side and reloadable:
 * <ul>
 *   <li>{@code starwake/reaction/*.json}: a balanced equation for a machine ("2 PbS + 3 O2 -> 2 PbO
 *       + 2 SO2"), its priority (lower runs first) and which reactants the machine may feed from
 *       its reagent slots, inlet tank or the air ({@code draw}). Unbalanced or unknown species are
 *       rejected with a log line.</li>
 *   <li>{@code starwake/separation/*.json}: flotation, magnetic and gravity separation settings.</li>
 * </ul>
 */
public final class Reactions {
	public record Term(String species, double coefficient) {
		/** kg of this term per mole of reaction extent. */
		public double kgPerMole() {
			return coefficient * Species.require(species).molarMass() / 1000.0;
		}
	}

	public record Reaction(Identifier id, String machine, int priority, List<Term> reactants, List<Term> products, List<String> draw,
			Optional<String> note, String equation) {}

	record Raw(String machine, String equation, int priority, List<String> draw, Optional<String> note) {
		static final Codec<Raw> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("machine").forGetter(Raw::machine),
				Codec.STRING.fieldOf("equation").forGetter(Raw::equation),
				Codec.INT.optionalFieldOf("priority", 50).forGetter(Raw::priority),
				Codec.STRING.listOf().optionalFieldOf("draw", List.of()).forGetter(Raw::draw),
				Codec.STRING.optionalFieldOf("note").forGetter(Raw::note)
		).apply(i, Raw::new));
	}

	/**
	 * A separation method.
	 *
	 * @param select       species flags that report to the concentrate
	 * @param weak         flags that report with {@code weakRecovery}
	 * @param minDensity   for gravity separation: species at least this dense (g/cm3) report
	 * @param recovery     share of selected species to the concentrate
	 * @param entrainment  share of everything else dragged into the concentrate
	 * @param water        kg of water consumed per kg of feed
	 * @param frother      optional reagent that raises recovery
	 */
	public record Separation(List<String> select, List<String> weak, double minDensity, double recovery, double weakRecovery, double entrainment,
			double water, Optional<Frother> frother, Optional<String> note) {
		static final Codec<Separation> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.listOf().optionalFieldOf("select", List.of()).forGetter(Separation::select),
				Codec.STRING.listOf().optionalFieldOf("weak", List.of()).forGetter(Separation::weak),
				Codec.DOUBLE.optionalFieldOf("min_density", 0.0).forGetter(Separation::minDensity),
				Codec.DOUBLE.fieldOf("recovery").forGetter(Separation::recovery),
				Codec.DOUBLE.optionalFieldOf("weak_recovery", 0.0).forGetter(Separation::weakRecovery),
				Codec.DOUBLE.optionalFieldOf("entrainment", 0.05).forGetter(Separation::entrainment),
				Codec.DOUBLE.optionalFieldOf("water", 0.0).forGetter(Separation::water),
				Frother.CODEC.optionalFieldOf("frother").forGetter(Separation::frother),
				Codec.STRING.optionalFieldOf("note").forGetter(Separation::note)
		).apply(i, Separation::new));
	}

	public record Frother(String species, double kg, double recovery) {
		static final Codec<Frother> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("species").forGetter(Frother::species),
				Codec.DOUBLE.fieldOf("kg").forGetter(Frother::kg),
				Codec.DOUBLE.fieldOf("recovery").forGetter(Frother::recovery)
		).apply(i, Frother::new));
	}

	private static Map<String, List<Reaction>> byMachine = Map.of();
	private static Map<String, Separation> separations = Map.of();

	private Reactions() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("reaction"),
				new SimpleJsonResourceReloadListener<>(Raw.CODEC, FileToIdConverter.json("starwake/reaction")) {
					@Override
					protected void apply(Map<Identifier, Raw> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Map<String, List<Reaction>> map = new HashMap<>();
						loaded.forEach((id, raw) -> parse(id, raw).ifPresent(r -> map.computeIfAbsent(r.machine(), k -> new ArrayList<>()).add(r)));
						map.values().forEach(l -> l.sort(Comparator.comparingInt(Reaction::priority)));
						byMachine = Map.copyOf(map);
						StarwakeMod.LOGGER.info("Loaded {} reactions for {} machines", loaded.size(), map.size());
					}
				});
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("separation"),
				new SimpleJsonResourceReloadListener<>(Separation.CODEC, FileToIdConverter.json("starwake/separation")) {
					@Override
					protected void apply(Map<Identifier, Separation> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Map<String, Separation> map = new HashMap<>();
						loaded.forEach((id, s) -> map.put(id.getPath(), s));
						separations = Map.copyOf(map);
					}
				});
	}

	public static List<Reaction> forMachine(String machine) {
		return byMachine.getOrDefault(machine, List.of());
	}

	public static Optional<Separation> separation(String method) {
		return Optional.ofNullable(separations.get(method));
	}

	private static Optional<Reaction> parse(Identifier id, Raw raw) {
		try {
			String[] sides = raw.equation().split("->");
			List<Term> left = terms(sides[0]), right = terms(sides[1]);
			Map<String, Double> balance = new HashMap<>();
			for (Term t : left) Species.parse(Species.require(t.species()).formula()).forEach((el, n) -> balance.merge(el, n * t.coefficient(), Double::sum));
			for (Term t : right) Species.parse(Species.require(t.species()).formula()).forEach((el, n) -> balance.merge(el, -n * t.coefficient(), Double::sum));
			for (var e : balance.entrySet()) {
				if (Math.abs(e.getValue()) > 1e-6) throw new IllegalArgumentException("unbalanced " + e.getKey() + " by " + e.getValue());
			}
			return Optional.of(new Reaction(id, raw.machine(), raw.priority(), left, right, raw.draw(), raw.note(), raw.equation()));
		} catch (RuntimeException e) {
			StarwakeMod.LOGGER.error("Bad reaction {}: {}", id, e.getMessage());
			return Optional.empty();
		}
	}

	private static List<Term> terms(String side) {
		List<Term> out = new ArrayList<>();
		for (String term : side.trim().split("\\s+\\+\\s+")) {
			term = term.trim();
			String[] parts = term.split("\\s+", 2);
			if (parts.length == 2 && Character.isDigit(parts[0].charAt(0))) out.add(new Term(parts[1], Double.parseDouble(parts[0])));
			else out.add(new Term(term, 1));
			Species.require(out.getLast().species());
		}
		return out;
	}
}
