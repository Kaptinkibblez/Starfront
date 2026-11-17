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
				shaped(RecipeCategory.BUILDING_BLOCKS, com.starwake.registry.ModBlocks.CANOPY_GLASS, 8).pattern("GGG").pattern("GIG").pattern("GGG")
						.define('G', Items.GLASS).define('I', Items.IRON_INGOT).unlockedBy("has_glass", has(Items.GLASS)).save(output);
				// Dry Dock (M10): gantry blocks to build ships on; the scan never takes them.
				shaped(RecipeCategory.BUILDING_BLOCKS, com.starwake.registry.ModBlocks.DRY_DOCK, 8).pattern("IBI").pattern("BYB").pattern("IBI")
						.define('I', Items.IRON_INGOT).define('B', Items.IRON_BARS).define('Y', Items.DYE.yellow())
						.unlockedBy("has_iron", has(Items.IRON_INGOT)).save(output);
				stairBuilder(com.starwake.registry.ModBlocks.HULL_PLATING_STAIRS, net.minecraft.world.item.crafting.Ingredient.of(com.starwake.registry.ModBlocks.HULL_PLATING))
						.unlockedBy("has_hull", has(com.starwake.registry.ModBlocks.HULL_PLATING)).save(output);
				slab(RecipeCategory.BUILDING_BLOCKS, com.starwake.registry.ModBlocks.HULL_PLATING_SLAB, com.starwake.registry.ModBlocks.HULL_PLATING);
				// Hull Panels (M9 pass 3): plain steel from plating (the Fabricator makes them in any alloy), and the Hull Tool.
				for (var piece : com.starwake.registry.ModBlocks.hullPieces()) {
					int n = piece == com.starwake.registry.ModBlocks.HULL_PLATE ? 4 : piece == com.starwake.registry.ModBlocks.HULL_HALF_PLATE || piece == com.starwake.registry.ModBlocks.HULL_FRAME ? 2 : 1;
					stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, piece, com.starwake.registry.ModBlocks.HULL_PLATING, n);
				}
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, com.starwake.registry.ModBlocks.GREEBLE, com.starwake.registry.ModBlocks.HULL_PLATING, 4);
				shaped(RecipeCategory.BUILDING_BLOCKS, com.starwake.registry.ModBlocks.SHIP_LIGHT, 4).pattern(" P ").pattern("IGI")
						.define('P', Items.GLASS_PANE).define('G', Items.GLOWSTONE_DUST).define('I', Items.IRON_INGOT)
						.unlockedBy("has_glowstone", has(Items.GLOWSTONE_DUST)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModBlocks.HINGE).pattern("III").pattern("IPI").pattern("ICI")
						.define('I', Items.IRON_INGOT).define('P', Items.PISTON).define('C', Items.COPPER_INGOT)
						.unlockedBy("has_piston", has(Items.PISTON)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModBlocks.ROTOR).pattern("III").pattern("IRI").pattern("ICI")
						.define('I', Items.IRON_INGOT).define('R', Items.REDSTONE_BLOCK).define('C', Items.COPPER_INGOT)
						.unlockedBy("has_redstone_block", has(Items.REDSTONE_BLOCK)).save(output);
				shaped(RecipeCategory.TOOLS, com.starwake.registry.ModItems.HULL_TOOL).pattern(" IC").pattern(" SI").pattern("S  ")
						.define('I', Items.IRON_INGOT).define('C', Items.COPPER_INGOT).define('S', Items.STICK)
						.unlockedBy("has_hull", has(com.starwake.registry.ModBlocks.HULL_PLATING)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.FLUID_TANK).pattern("IGI").pattern("G G").pattern("IGI")
						.define('I', Items.IRON_INGOT).define('G', Items.GLASS).unlockedBy("has_glass", has(Items.GLASS)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.CONVEYOR, 6).pattern("KKK").pattern("IRI")
						.define('K', Items.DRIED_KELP).define('I', Items.IRON_INGOT).define('R', Items.REDSTONE)
						.unlockedBy("has_iron", has(Items.IRON_INGOT)).save(output);
				// Trade Kiosk (M9 player shop).
				shaped(RecipeCategory.DECORATIONS, com.starwake.registry.ModHubBlocks.TRADE_KIOSK).pattern("IGI").pattern("ICI").pattern("III")
						.define('I', Items.IRON_INGOT).define('G', Items.GLASS_PANE).define('C', Items.CHEST)
						.unlockedBy("has_chest", has(Items.CHEST)).save(output);
				// Belt pieces (M9): built from belts.
				shaped(RecipeCategory.REDSTONE, ModMachines.CONVEYOR_SPLITTER, 2).pattern(" B ").pattern("BRB")
						.define('B', ModMachines.CONVEYOR).define('R', Items.REDSTONE).unlockedBy("has_belt", has(ModMachines.CONVEYOR)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.CONVEYOR_FILTER).pattern("C").pattern("B")
						.define('B', ModMachines.CONVEYOR).define('C', Items.COMPARATOR).unlockedBy("has_belt", has(ModMachines.CONVEYOR)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.CONVEYOR_INCLINE, 2).pattern(" B").pattern("BI")
						.define('B', ModMachines.CONVEYOR).define('I', Items.IRON_INGOT).unlockedBy("has_belt", has(ModMachines.CONVEYOR)).save(output);
				shapeless(RecipeCategory.REDSTONE, ModMachines.CONVEYOR_DECLINE).requires(ModMachines.CONVEYOR_INCLINE)
						.unlockedBy("has_belt", has(ModMachines.CONVEYOR)).save(output);
				shapeless(RecipeCategory.REDSTONE, ModMachines.CONVEYOR_INCLINE).requires(ModMachines.CONVEYOR_DECLINE)
						.unlockedBy("has_belt", has(ModMachines.CONVEYOR)).save(output, "starwake:conveyor_incline_from_decline");
				shaped(RecipeCategory.REDSTONE, ModMachines.ITEM_LIFT, 2).pattern("IBI").pattern("IRI").pattern("IBI")
						.define('B', ModMachines.CONVEYOR).define('I', Items.IRON_INGOT).define('R', Items.REDSTONE)
						.unlockedBy("has_belt", has(ModMachines.CONVEYOR)).save(output);
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
				machine(com.starwake.process.ProcessMachineBlockEntity.Process.SOLVENT_EXTRACTION, "GBG", "GCG", "IRI", Items.CAULDRON);
				shaped(RecipeCategory.REDSTONE, ModMachines.GAS_WELL).pattern("IPI").pattern("ICI").pattern("IRI")
						.define('I', Items.IRON_INGOT).define('P', Items.PISTON).define('C', ModMachines.COPPER_CABLE)
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.TOOLS, com.starwake.registry.ModProcess.GAS_CYLINDER).pattern(" I ").pattern("I I").pattern("III")
						.define('I', Items.IRON_INGOT).unlockedBy("has_iron", has(Items.IRON_INGOT)).save(output);
				// M8d-1 metalworking (provisional until 8i).
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.INDUCTION_FURNACE).pattern("CBC").pattern("CFC").pattern("IRI")
						.define('C', ModMachines.COPPER_CABLE).define('B', Items.BRICKS).define('F', Items.BLAST_FURNACE)
						.define('I', Items.IRON_INGOT).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.VACUUM_ARC_FURNACE).pattern("IGI").pattern("CFC").pattern("IRI")
						.define('C', ModMachines.COPPER_CABLE).define('G', Items.GLASS).define('F', com.starwake.registry.ModMetalworks.INDUCTION_FURNACE)
						.define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE)).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.SINTER_PRESS).pattern("IPI").pattern("CFC").pattern("IRI")
						.define('C', ModMachines.COPPER_CABLE).define('P', Items.PISTON).define('F', Items.FURNACE)
						.define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE)).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				// M8d-2 forming + heat treatment (provisional until 8i).
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.ROLLING_MILL).pattern("IBI").pattern("CBC").pattern("IRI")
						.define('B', Items.IRON_BLOCK).define('C', ModMachines.COPPER_CABLE).define('I', Items.IRON_INGOT).define('R', Items.REDSTONE)
						.unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.WIRE_DRAWER).pattern("IDI").pattern("CGC").pattern("IRI")
						.define('D', Items.DIAMOND).define('G', Items.GRINDSTONE).define('C', ModMachines.COPPER_CABLE).define('I', Items.IRON_INGOT)
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.EXTRUSION_PRESS).pattern("IPI").pattern("CBC").pattern("IRI")
						.define('P', Items.PISTON).define('B', Items.IRON_BLOCK).define('C', ModMachines.COPPER_CABLE).define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE))
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.FORGING_PRESS).pattern("IPI").pattern("CAC").pattern("IRI")
						.define('P', Items.PISTON).define('A', Items.ANVIL).define('C', ModMachines.COPPER_CABLE).define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE))
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModMetalworks.HEAT_TREAT_FURNACE).pattern("BBB").pattern("CFC").pattern("IRI")
						.define('B', Items.BRICKS).define('F', Items.FURNACE).define('C', ModMachines.COPPER_CABLE).define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE))
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				// M8g nuclear machines are fabricated from components (tools/fabrication.py), not crafted (M8i).
				// M8f heavy-water column (provisional until 8i).
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModProcess.block(com.starwake.process.ProcessMachineBlockEntity.Process.ISOTOPE_COLUMN))
						.pattern("IGI").pattern("IGI").pattern("CRC").define('G', Items.GLASS).define('C', ModMachines.COPPER_CABLE)
						.define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE)).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				// M8e-1 fabrication machines (provisional until 8i).
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModProcess.block(com.starwake.process.ProcessMachineBlockEntity.Process.ARC_FURNACE))
						.pattern("CCC").pattern("BFB").pattern("IRI").define('C', Items.COAL_BLOCK).define('B', Items.BRICKS)
						.define('F', com.starwake.registry.ModProcess.block(com.starwake.process.ProcessMachineBlockEntity.Process.SMELTER))
						.define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE)).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModFabrication.CRYSTAL_PULLER).pattern("IGI").pattern("CFC").pattern("IRI")
						.define('G', Items.GLASS).define('F', com.starwake.registry.ModMetalworks.INDUCTION_FURNACE).define('C', ModMachines.COPPER_CABLE)
						.define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE)).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModFabrication.CLEAN_FAB).pattern("QGQ").pattern("CDC").pattern("IRI")
						.define('Q', Items.QUARTZ_BLOCK).define('G', Items.GLASS).define('D', Items.DIAMOND).define('C', ModMachines.COPPER_CABLE)
						.define('I', com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE)).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModFabrication.FABRICATOR).pattern("IPI").pattern("CTC").pattern("IRI")
						.define('P', Items.PISTON).define('T', Items.CRAFTING_TABLE).define('C', ModMachines.COPPER_CABLE)
						.define('I', Items.IRON_INGOT).define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				String[][] mouldPatterns = {{"B B", "BCB", "BBB"}, {"BCB", "BCB", "BBB"}, {"BCB", "B B", "BBB"}};
				var moulds = com.starwake.registry.ModMetalworks.moulds();
				for (int i = 0; i < moulds.size(); i++) {
					shaped(RecipeCategory.TOOLS, moulds.get(i)).pattern(mouldPatterns[i][0]).pattern(mouldPatterns[i][1]).pattern(mouldPatterns[i][2])
							.define('B', Items.BRICK).define('C', Items.CLAY_BALL).unlockedBy("has_brick", has(Items.BRICK)).save(output);
				}
				shaped(RecipeCategory.REDSTONE, ModMachines.WATER_PUMP).pattern("IPI").pattern("CBC").pattern("IRI")
						.define('I', Items.IRON_INGOT).define('P', Items.PISTON).define('C', ModMachines.COPPER_CABLE).define('B', Items.BUCKET)
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
				shaped(RecipeCategory.REDSTONE, ModMachines.ATMOSPHERIC_CONDENSER).pattern("III").pattern("CBC").pattern("IRI")
						.define('I', Items.IRON_BARS).define('C', ModMachines.COPPER_CABLE).define('B', Items.BUCKET)
						.define('R', Items.REDSTONE).unlockedBy("has_cable", has(ModMachines.COPPER_CABLE)).save(output);
			}

			private static final net.minecraft.world.item.Item PLATE = com.starwake.metal.MetalItems.item(com.starwake.metal.MetalForm.PLATE);

			private void machine(com.starwake.process.ProcessMachineBlockEntity.Process p, String a, String b, String c,
					net.minecraft.world.item.Item special) {
				var builder = shaped(RecipeCategory.REDSTONE, com.starwake.registry.ModProcess.block(p)).pattern(a).pattern(b).pattern(c);
				String all = a + b + c;
				// M8i progression: wet chemistry and separation machines need metal plates (roll any ingot in the Rolling Mill).
				boolean tier1 = java.util.EnumSet.of(com.starwake.process.ProcessMachineBlockEntity.Process.REACTOR,
						com.starwake.process.ProcessMachineBlockEntity.Process.LEACH, com.starwake.process.ProcessMachineBlockEntity.Process.EVAPORATOR,
						com.starwake.process.ProcessMachineBlockEntity.Process.CRYSTALLIZER, com.starwake.process.ProcessMachineBlockEntity.Process.ELECTROLYSIS,
						com.starwake.process.ProcessMachineBlockEntity.Process.CHLORINATOR, com.starwake.process.ProcessMachineBlockEntity.Process.KROLL,
						com.starwake.process.ProcessMachineBlockEntity.Process.AIR_SEPARATOR,
						com.starwake.process.ProcessMachineBlockEntity.Process.SOLVENT_EXTRACTION).contains(p);
				if (all.indexOf('I') >= 0) builder.define('I', tier1 ? PLATE : Items.IRON_INGOT);
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
