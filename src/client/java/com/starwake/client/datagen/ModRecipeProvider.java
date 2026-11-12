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
				// Process machines (M8c-1), provisional vanilla-material recipes until 8i.
				var P = com.starwake.process.ProcessMachineBlockEntity.Process.class;
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.CRUSHER, "IPI", "ICI", "IRI", Items.PISTON);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.FLOTATION, "IBI", "GCG", "IRI", Items.BUCKET);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.MAGNETIC, "ILI", "ICI", "IRI", Items.LODESTONE);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.GRAVITY, "SSS", "ICI", "IRI", Items.SMOOTH_STONE_SLAB);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.ROASTER, "BBB", "BCB", "IRI", Items.BRICKS);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.COKE_OVEN, "BBB", "BFB", "BBB", Items.BRICKS);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.SMELTER, "BBB", "BCB", "IRI", Items.NETHER_BRICKS);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.CONVERTER, "I I", "ICI", "BRB", Items.NETHER_BRICKS);
				// M8c-2 wet chemistry.
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.REACTOR, "IGI", "ICI", "IRI", Items.CAULDRON);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.LEACH, "I I", "ICI", "IBI", Items.CAULDRON);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.EVAPORATOR, "GGG", "ICI", "IFI", Items.CAULDRON);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.CRYSTALLIZER, "IGI", "GBG", "IRI", Items.CAULDRON);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.ELECTROLYSIS, "CRC", "IBI", "III", Items.CAULDRON);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.CHLORINATOR, "BBB", "ICI", "IRI", Items.BRICKS);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.KROLL, "III", "BCB", "BRB", Items.BRICKS);
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.AIR_SEPARATOR, "GBG", "ICI", "IRI", Items.BLUE_ICE);
				shaped(RecipeCategory.TOOLS, com.starwake.registry.ModProcess.GAS_CYLINDER).pattern(" I ").pattern("I I").pattern("III")
						.define('I', Items.IRON_INGOT).unlockedBy("has_iron", has(Items.IRON_INGOT)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.WATER_PUMP).pattern("IPI").pattern("CBC").pattern("IRI")
						.define('I', Items.IRON_INGOT).define('P', Items.PISTON).define('C', ModMachines.COPPER_CABLE).define('B', Items.BUCKET)
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.ATMOSPHERIC_CONDENSER).pattern("III").pattern("CBC").pattern("IRI")
						.define('I', Items.IRON_BARS).define('C', ModMachines.COPPER_CABLE).define('B', Items.BUCKET)
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
			}

			private void machine(com.starwake.process.ProcessMachineBlockEntity.Process p, String a, String b, String c,
					net.minecraft.world.item.Item special) {
				var builder = shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModProcess.block(p)).pattern(a).pattern(b).pattern(c);
				String all = a + b + c;
				if (all.indexOf('I') >= 0) builder.define('I', Items.IRON_INGOT);
				if (all.indexOf('R') >= 0) builder.define('R', Items.REDSTONE);
				if (all.indexOf('C') >= 0) builder.define('C', ModMachines.COPPER_CABLE);
				if (all.indexOf('G') >= 0) builder.define('G', Items.GLASS);
				if (all.indexOf('F') >= 0) builder.define('F', Items.FURNACE);
				for (char k : new char[] {'P', 'B', 'L', 'S'}) if (all.indexOf(k) >= 0) builder.define(k, special);
				builder.unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
			}
		};
	}

	@Override
	public String getName() {
		return "Starwake recipes";
	}
}
