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

	private ModCreativeTabs() {}

	public static void init() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN_KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable(MAIN_TITLE_KEY))
				.icon(() -> new ItemStack(ModBlocks.HULL_PLATING))
				.displayItems((parameters, output) -> {
					ModBlocks.all().forEach(output::accept);
					ModItems.all().forEach(output::accept);
				})
				.build());
	}
}
