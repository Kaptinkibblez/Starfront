package com.starwake.registry;

import com.starwake.StarwakeMod;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Starwake's creative inventory tab. It lists every registered block and item automatically,
 * so new content shows up without editing this class.
 */
public final class ModCreativeTabs {
	public static final ResourceKey<CreativeModeTab> MAIN_KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, StarwakeMod.id("main"));

	/** Translation key for the tab title; the text itself comes from the generated lang file. */
	public static final String MAIN_TITLE_KEY = "itemGroup.starwake.main";

	public static final ResourceKey<CreativeModeTab> ELEMENTS_KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, StarwakeMod.id("elements"));
	public static final String ELEMENTS_TITLE_KEY = "itemGroup.starwake.elements";

	public static final ResourceKey<CreativeModeTab> MATERIALS_KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, StarwakeMod.id("materials"));
	public static final String MATERIALS_TITLE_KEY = "itemGroup.starwake.materials";

	private ModCreativeTabs() {}

	public static void init() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN_KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable(MAIN_TITLE_KEY))
				.icon(() -> new ItemStack(ModBlocks.HULL_PLATING))
				.displayItems((parameters, output) -> {
					ModBlocks.all().forEach(output::accept);
					// Every other Hull Panel shape, pre-set (the item places with that shape).
					for (com.starwake.ship.panel.PanelShape shape : com.starwake.ship.panel.PanelShape.values()) {
						if (shape == com.starwake.ship.panel.PanelShape.SLOPE) continue;
						ItemStack panel = new ItemStack(ModBlocks.HULL_PANEL);
						panel.set(net.minecraft.core.component.DataComponents.BLOCK_STATE, net.minecraft.world.item.component.BlockItemStateProperties.EMPTY
								.with(com.starwake.ship.block.HullPanelBlock.SHAPE, shape));
						output.accept(panel);
					}
					for (com.starwake.ship.panel.GreebleShape shape : com.starwake.ship.panel.GreebleShape.values()) {
						if (shape == com.starwake.ship.panel.GreebleShape.VENT) continue;
						ItemStack greeble = new ItemStack(ModBlocks.GREEBLE);
						greeble.set(net.minecraft.core.component.DataComponents.BLOCK_STATE, net.minecraft.world.item.component.BlockItemStateProperties.EMPTY
								.with(com.starwake.ship.block.GreebleBlock.SHAPE, shape));
						output.accept(greeble);
					}
					for (com.starwake.ship.panel.LightType type : com.starwake.ship.panel.LightType.values()) {
						for (com.starwake.ship.panel.LightType.Color color : com.starwake.ship.panel.LightType.Color.values()) {
							if (type == com.starwake.ship.panel.LightType.STRIP && color == com.starwake.ship.panel.LightType.Color.WHITE) continue;
							if (type != com.starwake.ship.panel.LightType.BEACON && type != com.starwake.ship.panel.LightType.RUNNING
									&& color != com.starwake.ship.panel.LightType.Color.WHITE) continue;
							ItemStack light = new ItemStack(ModBlocks.SHIP_LIGHT);
							light.set(net.minecraft.core.component.DataComponents.BLOCK_STATE, net.minecraft.world.item.component.BlockItemStateProperties.EMPTY
									.with(com.starwake.ship.block.ShipLightBlock.TYPE, type).with(com.starwake.ship.block.ShipLightBlock.COLOR, color));
							output.accept(light);
						}
					}
					ModItems.all().forEach(output::accept);
					ModMetalworks.moulds().forEach(output::accept);
					output.accept(ModProcess.GAS_CYLINDER);
					output.accept(ModFabrication.SILICON_WAFER);
					output.accept(ModFabrication.MICROCHIP);
					ModFabrication.components().forEach(output::accept);
					ModGear.all().forEach(output::accept);
				})
				.build());
		// The periodic table, in atomic-number order (vanilla ingots for Fe, Cu, Au).
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ELEMENTS_KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable(ELEMENTS_TITLE_KEY))
				.icon(() -> new ItemStack(BuiltInRegistries.ITEM.getValue(StarwakeMod.id("titanium_ingot"))))
				.displayItems((parameters, output) -> com.starwake.chem.Chemistry.elements().forEach(e ->
						output.accept(BuiltInRegistries.ITEM.getValue(e.item()))))
				.build());
		// Metallurgy (M8a): every exact alloy grade at its nominal composition, as ingot and plate.
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MATERIALS_KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable(MATERIALS_TITLE_KEY))
				.icon(() -> com.starwake.metal.MetalItems.stack(com.starwake.metal.MetalForm.INGOT,
						com.starwake.metal.MetalItems.VANILLA_IRON, 1))
				.displayItems((parameters, output) -> {
					var grades = parameters.holders().lookup(com.starwake.metal.AlloyGrade.REGISTRY_KEY);
					if (grades.isEmpty()) return;
					grades.get().listElements().map(net.minecraft.core.Holder.Reference::value)
							.filter(com.starwake.metal.AlloyGrade::exact)
							.sorted(java.util.Comparator.comparingInt(com.starwake.metal.AlloyGrade::order))
							.forEach(g -> {
								for (var form : com.starwake.metal.MetalForm.values()) {
									output.accept(com.starwake.metal.MetalItems.stack(form, g.nominalComposition(), 1));
								}
							});
				})
				.build());
	}
}
