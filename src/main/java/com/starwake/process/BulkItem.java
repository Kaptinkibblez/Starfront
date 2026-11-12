package com.starwake.process;

import com.starwake.chem.Chemistry;
import com.starwake.registry.ModComponents;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/**
 * A kilogram of process material (M8c): crushed ore, concentrate, tailings, calcine, matte, slag,
 * flue dust or coke. What it is made of lives in the {@code starwake:assay} component; the name
 * comes from its main species ("Chalcopyrite Concentrate") or, for matte, its main metal ("Copper
 * Matte"), and the tint from that species' colour.
 */
public class BulkItem extends Item {
	public enum Kind {
		CRUSHED_ORE("crushed_ore"), CONCENTRATE("concentrate"), TAILINGS("tailings"), CALCINE("calcine"), MATTE("matte"), SLAG("slag"),
		FLUE_DUST("flue_dust"), CHAR("coke"), RESIDUE("residue"), CRYSTALS("crystals"), SLIMES("slimes");

		public final String id;

		Kind(String id) {
			this.id = id;
		}
	}

	private final Kind kind;

	public BulkItem(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	@Override
	public Component getName(ItemStack stack) {
		Assay assay = stack.get(ModComponents.ASSAY);
		if (assay == null || assay.isEmpty()) return super.getName(stack);
		Component what;
		if (kind == Kind.MATTE) {
			String metal = mainMetal(assay);
			what = Chemistry.element(metal).map(e -> (Component) Component.translatable("element.starwake." + e.name())).orElse(Component.literal(metal));
		} else if (kind == Kind.CHAR) {
			return super.getName(stack);
		} else {
			what = Species.get(assay.mainSpecies()).map(Species.Info::displayName).orElse(Component.literal(assay.mainSpecies()));
		}
		return Component.translatable("item.starwake.bulk_" + kind.id + ".of", what);
	}

	/** The heaviest element in a material other than sulfur, oxygen and iron (unless iron is all there is). */
	public static String mainMetal(Assay assay) {
		String best = "Fe";
		double most = 0;
		for (Map.Entry<String, Double> e : assay.elements().entrySet()) {
			if (List.of("S", "O", "Fe", "Si").contains(e.getKey())) continue;
			if (e.getValue() > most) {
				most = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/** A bulk stack with its assay and tint. */
	public static ItemStack stack(BulkItem item, Assay assay, int count) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(ModComponents.ASSAY, assay);
		int color = Species.get(assay.mainSpecies()).map(Species.Info::color).orElse(0x888888);
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), List.of(color)));
		return stack;
	}
}
