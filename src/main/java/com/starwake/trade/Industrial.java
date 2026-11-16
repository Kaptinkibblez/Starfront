package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.metal.Composition;
import com.starwake.metal.MetalItems;
import com.starwake.metal.Metallurgy;
import com.starwake.process.Assay;
import com.starwake.process.BulkItem;
import com.starwake.registry.ModComponents;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Industrial trade (M9): hubs buy what the industry makes, valued from what it is: metals by their composition (element
 * mass x element price, from {@code tools/trade_data.py}) with premiums for a certified grade, purity, form and temper;
 * bulk ores and residues by their recoverable (non-gangue) element content; components, electronics and Mk parts from a
 * price table scaled by their material quality. Every product falls in a category; each hub kind buys some categories
 * at its own multiplier ({@code demand}), and selling a lot of one category pushes its price down for a while
 * (the saturation lives in {@link Markets}). Numbers: {@code data/starwake/starwake/industrial/default.json}.
 */
public final class Industrial {
	public record Tuning(Map<String, Double> elementPrice, Map<String, ItemPrice> items, Map<String, Double> kind,
			List<List<Double>> purity, Map<String, Double> form, Map<String, Double> temper, Map<String, Double> bulk,
			List<String> gangue, Map<String, Map<String, Double>> demand, double capacity, double tau, double spread) {
		public static final Codec<Tuning> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("element_price").forGetter(Tuning::elementPrice),
				Codec.unboundedMap(Codec.STRING, ItemPrice.CODEC).fieldOf("items").forGetter(Tuning::items),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("kind").forGetter(Tuning::kind),
				Codec.DOUBLE.listOf().listOf().fieldOf("purity").forGetter(Tuning::purity),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("form").forGetter(Tuning::form),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("temper").forGetter(Tuning::temper),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("bulk").forGetter(Tuning::bulk),
				Codec.STRING.listOf().fieldOf("gangue").forGetter(Tuning::gangue),
				Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)).fieldOf("demand").forGetter(Tuning::demand),
				Codec.DOUBLE.fieldOf("capacity").forGetter(Tuning::capacity),
				Codec.DOUBLE.fieldOf("tau").forGetter(Tuning::tau),
				Codec.DOUBLE.fieldOf("spread").forGetter(Tuning::spread)
		).apply(i, Tuning::new));
	}

	/** A table price: category and credits per item. */
	public record ItemPrice(String category, double price) {
		public static final Codec<ItemPrice> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("category").forGetter(ItemPrice::category),
				Codec.DOUBLE.fieldOf("price").forGetter(ItemPrice::price)
		).apply(i, ItemPrice::new));
	}

	/** What one item is worth to the market before demand and saturation. */
	public record Appraisal(String category, double value) {}

	private static Tuning tuning;

	private Industrial() {}

	static void set(Map<Identifier, Tuning> loaded) {
		tuning = loaded.getOrDefault(StarwakeMod.id("default"), loaded.values().stream().findFirst().orElse(null));
	}

	public static Optional<Tuning> tuning() {
		return Optional.ofNullable(tuning);
	}

	/** The category and fair value of one item of this stack, if the industrial market deals in it. */
	public static Optional<Appraisal> appraise(ItemStack stack) {
		Tuning t = tuning;
		if (t == null || stack.isEmpty()) return Optional.empty();
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		float quality = stack.getOrDefault(ModComponents.QUALITY, 1.0f);
		ItemPrice table = t.items().get(id);
		if (table != null) return Optional.of(new Appraisal(table.category(), table.price() * Math.clamp(quality, 0.5, 1.5)));
		if (stack.getItem() instanceof BulkItem bulk) {
			Assay assay = stack.get(ModComponents.ASSAY);
			if (assay == null) return Optional.empty();
			double share = t.bulk().getOrDefault(bulk.kind().id, 0.0);
			Set<String> gangue = Set.copyOf(t.gangue());
			double value = 0;
			for (var e : assay.elements().entrySet()) {
				if (!gangue.contains(e.getKey())) value += e.getValue() * t.elementPrice().getOrDefault(e.getKey(), 0.0);
			}
			value *= share; // 1 kg each
			return value >= 0.5 ? Optional.of(new Appraisal("ore", value)) : Optional.empty();
		}
		if (!(stack.getItem() instanceof com.starwake.metal.MetalItem)) return Optional.empty();
		Composition c = MetalItems.compositionOf(stack).orElse(null);
		if (c == null) return Optional.empty();
		double kg = MetalItems.formOf(stack).map(f -> f.kg).orElse(1.0);
		double value = 0;
		for (Composition.Part p : c.parts()) value += p.percent() / 100 * t.elementPrice().getOrDefault(p.element(), 0.0);
		value *= kg;
		Metallurgy.Analysis a = Metallurgy.analyze(c);
		value *= t.kind().getOrDefault(a.kind().name(), 1.0);
		if (a.kind() == Metallurgy.Kind.PURE) {
			for (List<Double> step : t.purity()) {
				if (step.size() == 2 && a.purity() >= step.get(0)) {
					value *= step.get(1);
					break;
				}
			}
		}
		value *= MetalItems.formOf(stack).map(f -> t.form().getOrDefault(f.id, 1.0)).orElse(1.0);
		var temper = stack.get(ModComponents.TEMPER);
		if (temper != null && !temper.state().isEmpty()) {
			value *= t.temper().getOrDefault(temper.state(), t.temper().getOrDefault("default", 1.0));
		}
		return Optional.of(new Appraisal(category(c.base()), value));
	}

	/** The metal category of a base element. */
	static String category(String base) {
		return switch (base) {
			case "Fe" -> "ferrous";
			case "Al", "Ti", "Mg", "Li", "Be" -> "light";
			case "Ni", "Co" -> "nickel_cobalt";
			case "Cu", "Zn", "Sn", "Pb" -> "copper";
			case "Au", "Ag", "Pt", "Pd", "Rh", "Ir", "Os", "Ru" -> "precious";
			case "W", "Mo", "Ta", "Nb", "Re", "Zr", "Hf", "V", "Cr" -> "refractory";
			default -> "other_metal";
		};
	}

	/** The multiplier a hub economy pays for a category, or 0 if it doesn't buy it. */
	public static double demand(String economy, String category) {
		Tuning t = tuning;
		if (t == null) return 0;
		return t.demand().getOrDefault(economy, Map.of()).getOrDefault(category, 0.0);
	}
}
