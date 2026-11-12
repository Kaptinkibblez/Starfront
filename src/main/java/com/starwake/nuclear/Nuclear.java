package com.starwake.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
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
 * Nuclear data (M8g), {@code data/starwake/starwake/nuclear/default.json} (from {@code tools/nuclear.py}).
 *
 * @param centrifugeBeta   heads separation factor of one Gas Centrifuge pass on the U-235/U-238 abundance ratio
 * @param centrifugeJPerKg energy per kg of UF6 fed
 * @param minEnrichment    moderator species -> lowest U-235 share that goes critical (light water ~3%, heavy water natural)
 * @param joulesPerKg      thermal energy per kg of U-235 fissioned (8.2e13)
 * @param efficiency       thermal -> electric
 * @param burnScale        game-time compression of fuel burn (reactors would otherwise run for months)
 * @param conversion       Pu-239 bred per U-235 fissioned (U-238 captures; ~0.6 in a light-water reactor)
 * @param spentFraction    fuel leaves the core when its U-235 share falls to this fraction of the moderator's minimum
 * @param fissionYields    element -> share of fission-product mass (Cs, Sr, Zr, Mo, Tc, Ru, Xe, Nd, Ce, La, Ba, Pm, I...)
 * @param irradiation      neutron-capture chains: target element -> product element, seconds at full power
 * @param accelerator      heavy-ion fusion-evaporation: target + beam -> product, energy (J)
 * @param fusionJPerKg     D-He3 energy release per kg of fuel (3.5e14), and the fusion reactor's efficiency
 */
public record Nuclear(double centrifugeBeta, double centrifugeJPerKg, Map<String, Double> minEnrichment, double joulesPerKg, double efficiency,
		double burnScale, double conversion, double spentFraction, Map<String, Double> fissionYields, List<Irradiation> irradiation,
		List<Collision> accelerator, double fusionJPerKg, double fusionEfficiency) {
	public record Irradiation(String target, String product, double seconds) {
		static final Codec<Irradiation> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("target").forGetter(Irradiation::target),
				Codec.STRING.fieldOf("product").forGetter(Irradiation::product),
				Codec.DOUBLE.fieldOf("seconds").forGetter(Irradiation::seconds)
		).apply(i, Irradiation::new));
	}

	public record Collision(String target, String beam, String product, double energy) {
		static final Codec<Collision> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("target").forGetter(Collision::target),
				Codec.STRING.fieldOf("beam").forGetter(Collision::beam),
				Codec.STRING.fieldOf("product").forGetter(Collision::product),
				Codec.DOUBLE.fieldOf("energy").forGetter(Collision::energy)
		).apply(i, Collision::new));
	}

	static final Codec<Nuclear> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.fieldOf("centrifuge_beta").forGetter(Nuclear::centrifugeBeta),
			Codec.DOUBLE.fieldOf("centrifuge_j_per_kg").forGetter(Nuclear::centrifugeJPerKg),
			Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("min_enrichment").forGetter(Nuclear::minEnrichment),
			Codec.DOUBLE.fieldOf("joules_per_kg").forGetter(Nuclear::joulesPerKg),
			Codec.DOUBLE.fieldOf("efficiency").forGetter(Nuclear::efficiency),
			Codec.DOUBLE.fieldOf("burn_scale").forGetter(Nuclear::burnScale),
			Codec.DOUBLE.fieldOf("conversion").forGetter(Nuclear::conversion),
			Codec.DOUBLE.fieldOf("spent_fraction").forGetter(Nuclear::spentFraction),
			Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("fission_yields").forGetter(Nuclear::fissionYields),
			Irradiation.CODEC.listOf().fieldOf("irradiation").forGetter(Nuclear::irradiation),
			Collision.CODEC.listOf().fieldOf("accelerator").forGetter(Nuclear::accelerator),
			Codec.DOUBLE.fieldOf("fusion_j_per_kg").forGetter(Nuclear::fusionJPerKg),
			Codec.DOUBLE.fieldOf("fusion_efficiency").forGetter(Nuclear::fusionEfficiency)
	).apply(i, Nuclear::new));

	private static Nuclear current = new Nuclear(1.6, 2e6, Map.of("H2O", 0.03, "D2O", 0.0071), 8.2e13, 0.33, 1000, 0.6, 0.35,
			Map.of("Cs", 0.15, "Sr", 0.1, "Zr", 0.15, "Mo", 0.12, "Xe", 0.12, "Nd", 0.1, "Ru", 0.08, "Ce", 0.08, "Tc", 0.05, "Ba", 0.05),
			List.of(), List.of(), 3.5e14, 0.4);

	public static Nuclear get() {
		return current;
	}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("nuclear"),
				new SimpleJsonResourceReloadListener<>(CODEC, FileToIdConverter.json("starwake/nuclear")) {
					@Override
					protected void apply(Map<Identifier, Nuclear> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Nuclear n = loaded.get(StarwakeMod.id("default"));
						if (n != null) current = n;
					}
				});
	}

	/** The element an item is (any element item: ingot, powder, canister, flask, sample) or is mostly made of; "" if none. */
	public static String elementOf(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty()) return "";
		var element = com.starwake.chem.Chemistry.elementOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
		var composition = stack.get(com.starwake.registry.ModComponents.COMPOSITION);
		if (composition != null && !composition.isEmpty()) return composition.base();
		if (element.isPresent()) return element.get().symbol();
		return com.starwake.metal.MetalItems.compositionOf(stack).map(com.starwake.metal.Composition::base).orElse("");
	}

	public Optional<Irradiation> irradiationOf(String element) {
		return irradiation.stream().filter(r -> r.target().equals(element)).findFirst();
	}

	public Optional<Collision> collision(String target, String beam) {
		return accelerator.stream().filter(r -> r.target().equals(target) && r.beam().equals(beam)).findFirst();
	}
}
