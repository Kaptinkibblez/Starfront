package com.starwake.client.datagen;

import com.starwake.registry.ModMachines;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Crafting recipes. M8b adds provisional recipes for the power and logistics blocks from vanilla
 * materials so they can be built in survival; 8i rebalances everything around Starwake's metals
 * (copper wire, steel plate, silicon wafers...).
 */
public class ModRecipeProvider extends FabricRecipeProvider {
	public ModRecipeProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, BootstrapContext<Recipe<?>> recipes,
			BootstrapContext<Advancement> advancements) {
		return new RecipeProvider(recipes, advancements) {
			@Override
			public void buildRecipes() {
				shaped(RecipeCategory.REDSTONE, ModMachines.COMBUSTION_GENERATOR).pattern("III").pattern("IFI").pattern("CRC")
						.define('I', Items.IRON_INGOT).define('F', Items.FURNACE).define('C', Items.COPPER_INGOT).define('R', Items.REDSTONE)
						.unlockedBy("has_furnace", has(Items.FURNACE)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.ELECTRIC_FURNACE).pattern("III").pattern("CFC").pattern("IRI")
						.define('I', Items.IRON_INGOT).define('F', Items.FURNACE).define('C', ModMachines.COPPER_CABLE).define('R', Items.REDSTONE)
						.unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.SOLAR_PANEL, 2).pattern("GGG").pattern("QRQ").pattern("ICI")
						.define('G', Items.GLASS).define('Q', Items.QUARTZ).define('R', Items.REDSTONE).define('I', Items.IRON_INGOT)
						.define('C', ModMachines.COPPER_CABLE).unlockedBy("has_quartz", has(Items.QUARTZ)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.BATTERY).pattern("ICI").pattern("RRR").pattern("ICI")
						.define('I', Items.IRON_INGOT).define('C', Items.COPPER_INGOT).define('R', Items.REDSTONE_BLOCK)
						.unlockedBy("has_redstone_block", has(Items.REDSTONE_BLOCK)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.COPPER_CABLE, 8).pattern("KKK").pattern("CCC").pattern("KKK")
						.define('K', Items.DRIED_KELP).define('C', Items.COPPER_INGOT).unlockedBy("has_copper", has(Items.COPPER_INGOT)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.FLUID_PIPE, 8).pattern("CCC").pattern("   ").pattern("CCC")
						.define('C', Items.COPPER_INGOT).unlockedBy("has_copper", has(Items.COPPER_INGOT)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.FLUID_TANK).pattern("IGI").pattern("G G").pattern("IGI")
						.define('I', Items.IRON_INGOT).define('G', Items.GLASS).unlockedBy("has_glass", has(Items.GLASS)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.CONVEYOR, 6).pattern("KKK").pattern("IRI")
						.define('K', Items.DRIED_KELP).define('I', Items.IRON_INGOT).define('R', Items.REDSTONE)
						.unlockedBy("has_iron", has(Items.IRON_INGOT)).save(output);
			}
		};
	}

	@Override
	public String getName() {
		return "Starwake recipes";
	}
}
