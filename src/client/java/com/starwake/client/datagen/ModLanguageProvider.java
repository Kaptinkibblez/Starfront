package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModCreativeTabs;
import com.starwake.registry.ModItems;
import com.starwake.registry.ModPlanetBlocks;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/** Generates the English (en_us) translations. */
public final class ModLanguageProvider extends FabricLanguageProvider {
	public ModLanguageProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, "en_us", registries);
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
		builder.add(ModCreativeTabs.MAIN_TITLE_KEY, "Starwake");
		builder.add(ModBlocks.HULL_PLATING, "Hull Plating");
		builder.add(ModBlocks.REGOLITH, "Regolith");
		builder.add(ModBlocks.RUST_SAND, "Rust Sand");
		builder.add(ModBlocks.FERRITE_ORE, "Ferrite Ore");
		builder.add(ModItems.RAW_FERRITE, "Raw Ferrite");

		// Planet content (M5)
		builder.add(ModPlanetBlocks.ANORTHOSITE, "Anorthosite");
		builder.add(ModPlanetBlocks.FINE_REGOLITH, "Fine Regolith");
		builder.add(ModPlanetBlocks.VOLCANIC_ASH, "Volcanic Ash");
		builder.add(ModPlanetBlocks.PERMAFROST, "Permafrost");
		builder.add(ModPlanetBlocks.HALITE, "Halite");
		builder.add(ModPlanetBlocks.SELENITE, "Selenite");
		builder.add(ModPlanetBlocks.SELENITE_CLUSTER, "Selenite Cluster");
		builder.add(ModPlanetBlocks.MAT_TURF, "Mat Turf");
		builder.add(ModPlanetBlocks.TUFT_GRASS, "Tuft Grass");
		builder.add(ModPlanetBlocks.FERNWOOD_LOG, "Fernwood Log");
		builder.add(ModPlanetBlocks.FERNWOOD_FRONDS, "Fernwood Fronds");
		builder.add(ModPlanetBlocks.FUNGAL_STEM, "Fungal Stem");
		builder.add(ModPlanetBlocks.FUNGAL_CAP, "Fungal Cap");
		builder.add(ModPlanetBlocks.PUFFBALL, "Puffball");
		for (var ore : ModPlanetBlocks.ores()) {
			String name = title(ore.id());
			builder.add(ore.block(), name + " Ore");
			builder.add(ore.raw(), "Raw " + name);
		}
		for (var mineral : com.starwake.chem.Chemistry.minerals()) {
			builder.add("mineral.starwake." + mineral.id(), title(mineral.id()));
		}
		// The periodic table.
		builder.add(ModCreativeTabs.ELEMENTS_TITLE_KEY, "Starwake: Elements");
		for (var element : com.starwake.chem.Chemistry.elements()) {
			String name = title(element.name());
			builder.add("element.starwake." + element.name(), name);
			if (!element.item().getNamespace().equals("starwake")) continue;
			String form = switch (element.form()) {
				case "ingot" -> name + " Ingot";
				case "powder" -> name + " Powder";
				case "canister" -> name + " Canister";
				case "flask" -> name + " Flask";
				default -> name + " Sample";
			};
			builder.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(element.item()), form);
		}
		for (String[] c : new String[][] {{"alkali", "Alkali metal"}, {"alkaline_earth", "Alkaline earth metal"},
				{"transition", "Transition metal"}, {"post_transition", "Post-transition metal"}, {"metalloid", "Metalloid"},
				{"nonmetal", "Nonmetal"}, {"halogen", "Halogen"}, {"noble_gas", "Noble gas"}, {"lanthanide", "Lanthanide"},
				{"actinide", "Actinide"}}) {
			builder.add("chem.starwake.category." + c[0], c[1]);
		}
		builder.add("chem.starwake.element", "%s  ·  Z %s  ·  %s");
		builder.add("chem.starwake.properties", "%s u  ·  %s g/cm³  ·  melts %s K");
		builder.add("chem.starwake.radioactive", "Radioactive");
		builder.add("chem.starwake.synthetic", "Synthetic: made in reactors and accelerators");
		builder.add("chem.starwake.trace_occurrence", "Natural only in traces (uranium ores)");
		builder.add("chem.starwake.sources", "Sources: %s");
		builder.add("chem.starwake.mineral", "%s  ·  %s");
		builder.add("chem.starwake.trace", "Trace: %s");
		builder.add("chem.starwake.source.atmosphere", "atmosphere");
		builder.add("chem.starwake.source.water", "water");
		builder.add("chem.starwake.source.ice", "ice");
		builder.add("chem.starwake.source.brine", "brine");
		builder.add("chem.starwake.source.coal", "coal");
		// Trading (M7)
		builder.add("faction.starwake.solace_accord", "Solace Accord");
		builder.add("faction.starwake.solace_accord.description", "The civil authority of the Solace system: ports, customs and the trade lanes.");
		builder.add("faction.starwake.meridian_collective", "Meridian Mining Collective");
		builder.add("faction.starwake.meridian_collective.description", "Miners and refiners who claim the rocky, frozen and volcanic worlds.");
		builder.add("faction.starwake.halcyon_traders", "Halcyon Free Traders");
		builder.add("faction.starwake.halcyon_traders.description", "Independent merchant houses that run markets wherever a ship can dock.");
		builder.add("faction.starwake.verdant_institute", "Verdant Research Institute");
		builder.add("faction.starwake.verdant_institute.description", "Scientists who survey new worlds, life and minerals across the frontier.");
		builder.add("hub_economy.starwake.trade_port", "Trade Port");
		builder.add("hub_economy.starwake.refinery", "Refinery");
		builder.add("hub_economy.starwake.research_station", "Research Station");
		builder.add("hub_economy.starwake.settlement", "Settlement");
		builder.add("hub_economy.starwake.mining_camp", "Mining Camp");
		builder.add("hub_economy.starwake.research_post", "Research Post");
		builder.add("hub_economy.starwake.trading_post", "Trading Post");
		builder.add("hub_template.starwake.station_port", "Orbital Port");
		builder.add("hub_template.starwake.station_refinery", "Orbital Refinery");
		builder.add("hub_template.starwake.outpost_settlement", "Settlement");
		builder.add("hub_template.starwake.outpost_mining", "Mining Camp");
		builder.add("hub_template.starwake.outpost_research", "Research Post");
		builder.add("hub_kind.starwake.station", "Station");
		builder.add("hub_kind.starwake.outpost", "Outpost");
		builder.add("rank.starwake.hostile", "Hostile");
		builder.add("rank.starwake.unfriendly", "Unfriendly");
		builder.add("rank.starwake.neutral", "Neutral");
		builder.add("rank.starwake.friendly", "Friendly");
		builder.add("rank.starwake.trusted", "Trusted");
		builder.add("rank.starwake.honored", "Honored");
		builder.add("screen.starwake.terminal.empty", "Nothing here right now");
		builder.add("screen.starwake.market", "COMMODITY TERMINAL");
		builder.add("screen.starwake.market.buy", "Buy");
		builder.add("screen.starwake.market.sell", "Sell");
		builder.add("screen.starwake.market.commodity", "Commodity");
		builder.add("screen.starwake.market.stock", "Stock");
		builder.add("screen.starwake.market.buy_price", "Buy");
		builder.add("screen.starwake.market.sell_price", "Sell");
		builder.add("screen.starwake.outfitter", "OUTFITTER");
		builder.add("screen.starwake.outfitter.needs", "needs %s");
		builder.add("screen.starwake.contracts", "CONTRACT BOARD");
		builder.add("screen.starwake.contracts.accept", "Accept");
		builder.add("screen.starwake.contracts.complete", "Hand in");
		builder.add("screen.starwake.contracts.none", "No contracts today");
		builder.add("screen.starwake.contracts.offer", "%s · expires in %s");
		builder.add("screen.starwake.contracts.hand_in", "%s · ready to hand in here (%s left)");
		builder.add("contract.starwake.delivery", "Deliver %s %s to %s");
		builder.add("contract.starwake.mining", "Bring %s %s to %s");
		builder.add("contract.starwake.courier", "Carry sealed cargo to %s");
		builder.add("contract.starwake.exploration", "Survey the %s system");
		builder.add("contract.starwake.type.delivery", "Delivery");
		builder.add("contract.starwake.type.mining", "Mining quota");
		builder.add("contract.starwake.type.courier", "Courier");
		builder.add("contract.starwake.type.exploration", "Exploration");
		builder.add("message.starwake.trade.no_link", "This terminal isn't linked to a market");
		builder.add("message.starwake.trade.hostile", "Access refused: this faction considers you hostile");
		builder.add("message.starwake.trade.too_far", "You need to be at the hub to do that");
		builder.add("message.starwake.trade.not_traded", "Not traded here");
		builder.add("message.starwake.trade.out_of_stock", "Not enough in stock");
		builder.add("message.starwake.trade.no_credits", "You need %s credits");
		builder.add("message.starwake.trade.none_to_sell", "You have none to sell");
		builder.add("message.starwake.trade.full", "The hub won't take any more");
		builder.add("message.starwake.trade.bought", "Bought %s %s for %s cr");
		builder.add("message.starwake.trade.sold", "Sold %s %s for %s cr");
		builder.add("message.starwake.trade.rep_needed", "Requires %s reputation");
		builder.add("message.starwake.contract.too_many", "You already have three contracts");
		builder.add("message.starwake.contract.gone", "That contract is no longer available");
		builder.add("message.starwake.contract.no_room", "No room for the cargo");
		builder.add("message.starwake.contract.accepted", "Contract accepted: %s");
		builder.add("message.starwake.contract.not_here", "That contract isn't handed in here");
		builder.add("message.starwake.contract.no_cargo", "You don't have the sealed cargo");
		builder.add("message.starwake.contract.need", "You need %s %s");
		builder.add("message.starwake.contract.completed", "Contract complete: +%s cr");
		builder.add("message.starwake.contract.abandoned", "Contract abandoned");
		builder.add("message.starwake.contract.expired", "Contract expired: %s");
		builder.add("message.starwake.contract.explored", "Survey of %s complete: +%s cr");
		builder.add("item.starwake.sealed_cargo.for", "Sealed Cargo for %s");
		builder.add("wristcom.trade.credits", "Credits: %s cr");
		builder.add("wristcom.trade.standing", "Faction standing");
		builder.add("wristcom.trade.hubs", "Hubs in this system");
		builder.add("wristcom.trade.no_hubs", "No trade hubs in this system");
		builder.add("wristcom.trade.station_near", "orbiting %s");
		builder.add("wristcom.trade.on_planet", "on %s");
		builder.add("wristcom.contracts.active", "Active contracts: %s / %s");
		builder.add("wristcom.contracts.none", "None. Take contracts at a hub's Contract Board.");
		builder.add("wristcom.contracts.for", "for the %s");
		builder.add("wristcom.contracts.abandon", "Abandon");
		builder.add("entity.starwake.hub_npc", "Hub Crew");
		// Power and logistics (M8b).
		builder.add(com.starwake.registry.ModMachines.COMBUSTION_GENERATOR, "Combustion Generator");
		builder.add(com.starwake.registry.ModMachines.ELECTRIC_FURNACE, "Electric Furnace");
		builder.add(com.starwake.registry.ModMachines.SOLAR_PANEL, "Solar Panel");
		builder.add(com.starwake.registry.ModMachines.BATTERY, "Battery Bank");
		builder.add(com.starwake.registry.ModMachines.COPPER_CABLE, "Copper Cable");
		builder.add(com.starwake.registry.ModMachines.ALUMINIUM_CABLE, "Aluminium HV Cable");
		builder.add(com.starwake.registry.ModMachines.FLUID_PIPE, "Fluid Pipe");
		builder.add(com.starwake.registry.ModMachines.FLUID_TANK, "Fluid Tank");
		builder.add(com.starwake.registry.ModMachines.CONVEYOR, "Conveyor Belt");
		builder.add(com.starwake.registry.ModMachines.WATER_PUMP, "Water Pump");
		builder.add(com.starwake.registry.ModMachines.ATMOSPHERIC_CONDENSER, "Atmospheric Condenser");
		builder.add("message.starwake.grid", "Grid: %s kW supply · %s kW demand · %s machines, %s cables");
		builder.add("message.starwake.solar", "Solar %s kW (sun %s%%, starlight %s×)");
		builder.add("message.starwake.battery", "Battery %s / %s kWh (%s%%)");
		builder.add("message.starwake.pipe.extract", "Pipe side %s: extracting");
		builder.add("message.starwake.pipe.connect", "Pipe side %s: connected");
		builder.add("message.starwake.tank", "%s: %s / %s buckets");
		builder.add("message.starwake.tank.empty_fluid", "Empty");
		for (var d : net.minecraft.core.Direction.values()) {
			builder.add("direction.starwake." + d.getSerializedName(), d.getSerializedName().substring(0, 1).toUpperCase() + d.getSerializedName().substring(1));
		}
		builder.add("screen.starwake.machine.energy", "%s / %s kJ");
		builder.add("screen.starwake.machine.power_out", "Output %s kW");
		builder.add("screen.starwake.machine.power_in", "Drawing %s kW");
		builder.add("screen.starwake.machine.idle", "Idle");
		builder.add("screen.starwake.machine.grid", "Grid: %s kW in · %s kW use");
		builder.add("screen.starwake.machine.no_power", "No power");
		// Process chemistry (M8c-1).
		java.util.Map<String, String> machines = java.util.Map.ofEntries(java.util.Map.entry("crusher", "Jaw Crusher"),
				java.util.Map.entry("flotation", "Flotation Cell"), java.util.Map.entry("magnetic", "Magnetic Separator"),
				java.util.Map.entry("gravity", "Shaking Table"), java.util.Map.entry("roaster", "Roasting Kiln"), java.util.Map.entry("coke_oven", "Coke Oven"),
				java.util.Map.entry("smelter", "Electric Smelter"), java.util.Map.entry("converter", "Converter"),
				java.util.Map.entry("reactor", "Chemical Reactor"), java.util.Map.entry("leach", "Leach Tank"), java.util.Map.entry("evaporator", "Evaporator"),
				java.util.Map.entry("crystallizer", "Crystallizer"), java.util.Map.entry("electrolysis", "Electrolysis Cell"),
				java.util.Map.entry("chlorinator", "Chlorinator"), java.util.Map.entry("kroll", "Kroll Reactor"),
				java.util.Map.entry("air_separator", "Air Separation Unit"), java.util.Map.entry("solvent_extraction", "Solvent Extraction Battery"));
		for (var p : com.starwake.process.ProcessMachineBlockEntity.Process.values()) {
			builder.add(com.starwake.registry.ModProcess.block(p), machines.get(p.machine));
		}
		java.util.Map<String, String[]> bulk = new java.util.LinkedHashMap<>();
		bulk.put("crushed_ore", new String[] {"Crushed Ore", "Crushed %s Ore"});
		bulk.put("concentrate", new String[] {"Concentrate", "%s Concentrate"});
		bulk.put("tailings", new String[] {"Tailings", "%s Tailings"});
		bulk.put("calcine", new String[] {"Calcine", "%s Calcine"});
		bulk.put("matte", new String[] {"Matte", "%s Matte"});
		bulk.put("slag", new String[] {"Slag", "%s Slag"});
		bulk.put("flue_dust", new String[] {"Flue Dust", "%s Flue Dust"});
		bulk.put("coke", new String[] {"Coke", "Coke"});
		bulk.put("residue", new String[] {"Residue", "%s Residue"});
		bulk.put("crystals", new String[] {"Crystals", "%s Crystals"});
		bulk.put("slimes", new String[] {"Anode Slimes", "%s Anode Slimes"});
		bulk.forEach((k, v) -> {
			builder.add("item.starwake.bulk_" + k, v[0]);
			builder.add("item.starwake.bulk_" + k + ".of", v[1]);
		});
		builder.add("fluid.starwake.chemical", "Chemical");
		// M8c-3a.
		builder.add(com.starwake.registry.ModProcess.GAS_CYLINDER, "Gas Cylinder");
		builder.add("item.starwake.gas_cylinder.empty", "Empty Gas Cylinder");
		builder.add("item.starwake.gas_cylinder.of", "Gas Cylinder (%s)");
		builder.add("item.starwake.gas_cylinder.contents", "%s / %s kg");
		builder.add("item.starwake.gas_cylinder.suit", "Use while wearing a Survey Suit to refill its oxygen");
		builder.add("item.starwake.gas_cylinder.help", "Use on a tank or machine to fill; sneak-use to empty");
		builder.add("message.starwake.pipe.filter", "Pipe filter: %s");
		builder.add("message.starwake.pipe.filter_any", "anything");
		// M8c-3b.
		builder.add(com.starwake.registry.ModMachines.GAS_WELL, "Gas Well");
		builder.add("message.starwake.sx.cut", "Extracting %s and everything after it");
		builder.add("message.starwake.sx.auto", "Cut: automatic (between the first two present)");
		builder.add("screen.starwake.machine.sx_cut", "Extract ≥ %s");
		builder.add("screen.starwake.machine.sx_auto", "Cut: auto");
		builder.add("screen.starwake.machine.sx_hint", "Sneak-use: cut");
		builder.add("screen.starwake.machine.tank.solvent_extraction.inlet", "Feed (chlorides, NaOH + HCl)");
		builder.add("screen.starwake.machine.tank.solvent_extraction.gas", "Extract (heavier, stripped)");
		builder.add("screen.starwake.machine.tank.solvent_extraction.liquid", "Raffinate (lighter)");
		builder.add("screen.starwake.machine.tank.air_separator.inlet", "Feed gas (optional, else air)");
		builder.add("process.starwake.tooltip.u235", "U-235: %s%% of the uranium");
		builder.add("process.starwake.tooltip.u235_natural", "U-235: 0.72%% of the uranium (natural)");
		builder.add("process.starwake.tooltip.radioactive", "☢ %s · %s at 1 m");
		builder.add("hud.starwake.rad.rate", "☢ Dose rate");
		builder.add("hud.starwake.rad.dose", "Acute dose");
		builder.add("death.attack.starwake.radiation", "%1$s died of radiation sickness");
		builder.add("death.attack.starwake.radiation.player", "%1$s died of radiation sickness while fighting %2$s");
		builder.add("process.starwake.tooltip.feed", "%s kg chunk, made of:");
		builder.add("process.starwake.tooltip.more", "  ... %s more");
		builder.add("process.starwake.tooltip.elements", "Contains: %s");
		builder.add("screen.starwake.machine.tank_empty", "Empty");
		builder.add("screen.starwake.machine.tank.inlet", "Inlet (water, reagents, oxygen)");
		builder.add("screen.starwake.machine.tank.gas", "Off-gas outlet");
		builder.add("screen.starwake.machine.tank.liquid", "Liquid outlet");
		// Metallurgy (M8a).
		builder.add("itemGroup.starwake.materials", "Starwake: Materials");
		for (var form : com.starwake.metal.MetalForm.values()) {
			String name = form.id.substring(0, 1).toUpperCase() + form.id.substring(1);
			builder.add("item.starwake.metal_" + form.id, "Metal " + name);
			builder.add("item.starwake.metal_" + form.id + ".of", "%s " + name);
		}
		builder.add("metal.starwake.unknown", "Unknown Metal");
		// Metalworking (M8d-1).
		builder.add(com.starwake.registry.ModMetalworks.INDUCTION_FURNACE, "Induction Furnace");
		builder.add(com.starwake.registry.ModMetalworks.VACUUM_ARC_FURNACE, "Vacuum Arc Furnace");
		builder.add(com.starwake.registry.ModMetalworks.SINTER_PRESS, "Sintering Press");
		builder.add(com.starwake.registry.ModMetalworks.BILLET_MOULD, "Billet Mould");
		builder.add(com.starwake.registry.ModMetalworks.SLAB_MOULD, "Slab Mould");
		builder.add(com.starwake.registry.ModMetalworks.CASTING_MOULD, "Casting Mould");
		builder.add("metal.starwake.condition.as_cast", "As cast");
		builder.add("metal.starwake.condition.sintered", "Sintered");
		builder.add("metal.starwake.tooltip.condition", "Condition: %s");
		builder.add("screen.starwake.melt.empty", "Bath empty: add metal to melt");
		builder.add("screen.starwake.melt.bath", "Bath %s kg");
		builder.add("screen.starwake.melt.liquidus", "Liquidus %s °C · max %s °C");
		builder.add("screen.starwake.melt.no_slag", "No slag");
		builder.add("screen.starwake.melt.slag", "Slag %s kg (%s)");
		builder.add("screen.starwake.melt.target", "Target grade");
		builder.add("screen.starwake.melt.no_target", "none");
		builder.add("screen.starwake.melt.on_spec", "On spec: tap it");
		builder.add("screen.starwake.melt.add", "+ %s kg %s");
		builder.add("screen.starwake.melt.blow", "Blow down %s (-%s kg)");
		builder.add("screen.starwake.melt.tap_on", "Tapping: ON");
		builder.add("screen.starwake.melt.tap_off", "Tapping: OFF");
		builder.add("screen.starwake.melt.status.melting", "Melting charge");
		builder.add("screen.starwake.melt.status.too_hot", "Too refractory for this furnace");
		builder.add("screen.starwake.melt.status.full", "Bath full");
		builder.add("screen.starwake.melt.status.blowing", "Blowing oxygen");
		builder.add("screen.starwake.melt.status.vacuum", "Under vacuum");
		builder.add("screen.starwake.melt.status.air", "Open to air (argon inlet shields)");
		builder.add("screen.starwake.machine.tank.induction_furnace.inlet", "Gas inlet (O2 to blow, Ar to shield/AOD)");
		builder.add("screen.starwake.machine.tank.induction_furnace.gas", "Off-gas (CO2)");
		builder.add("screen.starwake.machine.tank.vacuum_arc_furnace.inlet", "Gas inlet");
		builder.add("screen.starwake.machine.tank.vacuum_arc_furnace.gas", "Off-gas");
		builder.add("metal.starwake.off_spec", "Off-spec %s");
		builder.add("metal.starwake.alloy", "%s Alloy");
		builder.add("metal.starwake.tooltip.grade", "Certified grade · %s");
		builder.add("metal.starwake.tooltip.grade_named", "Certified %s · %s");
		builder.add("metal.starwake.tooltip.off_spec", "Off-spec %s:");
		builder.add("metal.starwake.tooltip.deviation", "  %s %s%% (spec %s-%s%%)");
		builder.add("metal.starwake.tooltip.other", "other elements");
		builder.add("metal.starwake.tooltip.purity", "Purity %s%%");
		builder.add("metal.starwake.tooltip.estimated", "No grade: estimated properties");
		builder.add("metal.starwake.tooltip.strength", "%s g/cm³ · Yield %s · UTS %s MPa");
		builder.add("metal.starwake.tooltip.heat", "Service ≤ %s °C · Solidus %s °C");
		builder.add("metal.starwake.tooltip.other_props", "Conductivity %s%% IACS · Corrosion %s/10");
		builder.add("metal.starwake.tooltip.detail", "Elongation %s%% · E %s GPa · %s HV · %s MPa per g/cm³");
		builder.add("metal.starwake.tooltip.brittle", "Brittle: cracks under shock");
		builder.add("metal.starwake.tooltip.shift", "Hold Shift for more");
		builder.add("entity.starwake.hub_npc.guard", "Guard");
		builder.add("entity.starwake.hub_npc.crew", "Crew");
		builder.add(com.starwake.registry.ModHubBlocks.STATION_PANEL, "Station Panel");
		builder.add(com.starwake.registry.ModHubBlocks.STATION_FLOOR, "Station Floor");
		builder.add(com.starwake.registry.ModHubBlocks.LIGHT_PANEL, "Light Panel");
		builder.add(com.starwake.registry.ModHubBlocks.LANDING_PAD, "Landing Pad");
		builder.add(com.starwake.registry.ModHubBlocks.COMMODITY_TERMINAL, "Commodity Terminal");
		builder.add(com.starwake.registry.ModHubBlocks.OUTFITTER_TERMINAL, "Outfitter Terminal");
		builder.add(com.starwake.registry.ModHubBlocks.CONTRACT_BOARD, "Contract Board");
		builder.add(com.starwake.registry.ModHubBlocks.SEALED_CARGO, "Sealed Cargo");
		// Galaxy (M6)
		for (String[] c : new String[][] {{"o", "Blue giant (O)"}, {"b", "Blue-white star (B)"}, {"a", "White star (A)"},
				{"f", "Yellow-white star (F)"}, {"g", "Yellow dwarf (G)"}, {"k", "Orange dwarf (K)"}, {"m", "Red dwarf (M)"},
				{"white_dwarf", "White dwarf (D)"}, {"neutron_star", "Neutron star"}}) {
			builder.add("star_class.starwake." + c[0], c[1]);
		}
		builder.add("key.starwake.jump", "Jump drive (tap: arm, hold: jump)");
		builder.add("key.starwake.galaxy_map", "Galaxy map");
		builder.add("screen.starwake.galaxy", "Galaxy Map");
		builder.add("screen.starwake.galaxy.here", "You are at %s");
		builder.add("screen.starwake.galaxy.pick", "Click a star to inspect it");
		builder.add("screen.starwake.galaxy.binary", "Binary: + %s");
		builder.add("screen.starwake.galaxy.distance", "Distance: %s ly");
		builder.add("screen.starwake.galaxy.planets", "Planets: %s");
		builder.add("screen.starwake.galaxy.loading", "Scanning...");
		builder.add("screen.starwake.galaxy.set_jump", "Set Jump Target");
		builder.add("screen.starwake.galaxy.is_target", "Jump target set");
		builder.add("screen.starwake.galaxy.no_data", "No galaxy data (not connected?)");
		builder.add("screen.starwake.galaxy.help", "Left-drag rotate · Right-drag pan · Scroll zoom · Click select · F focus · H here · M close");
		builder.add("wristcom.starmap.galaxy", "Galaxy");
		builder.add("hud.starwake.jump.mode", "JUMP: %s");
		builder.add("hud.starwake.jump.travel", "JUMPING TO %s");
		builder.add("hud.starwake.jump.tunnel", "In the jump tunnel");
		builder.add("hud.starwake.jump.no_target", "Pick a star on the galaxy map (M)");
		builder.add("hud.starwake.jump.out_of_range", "Out of range");
		builder.add("hud.starwake.jump.gravity_well", "Too close to a gravity well");
		builder.add("hud.starwake.jump.no_fuel", "Not enough fuel");
		builder.add("hud.starwake.jump.spooling", "Spooling...");
		builder.add("hud.starwake.jump.align", "Align with the target star");
		builder.add("hud.starwake.jump.ready", "READY: hold J");
		builder.add("message.starwake.jump.no_drive", "This ship has no Jump Drive");
		builder.add("message.starwake.jump.unknown", "Unknown star system");
		builder.add("message.starwake.jump.here", "You're already in that system");
		builder.add("message.starwake.jump.on", "Jump drive armed");
		builder.add("message.starwake.jump.off", "Jump drive disarmed");
		builder.add("message.starwake.jump.target", "Jump target: %s");
		builder.add("message.starwake.jump.not_pilot", "Only the pilot can set a jump target");
		builder.add("message.starwake.jump.in_tunnel", "Can't while jumping");
		builder.add("message.starwake.jump.no_target", "No jump target: pick a star on the galaxy map (M)");
		builder.add("message.starwake.jump.out_of_range", "Target is out of jump range");
		builder.add("message.starwake.jump.not_spooled", "Jump drive still spooling");
		builder.add("message.starwake.jump.gravity_well", "Too close to a gravity well: fly further from planets and stars");
		builder.add("message.starwake.jump.no_fuel", "Not enough fuel for this jump");
		builder.add("message.starwake.jump.not_aligned", "Point the nose at the target star");
		builder.add("message.starwake.jump.engaged", "Jumping to %s (%s ly, %s)");
		builder.add("message.starwake.jump.arrived", "Arrived at %s");
		builder.add(ModItems.SURVEY_SUIT, "Survey Suit");
		for (String[] kind : new String[][] {{"lush", "Lush world"}, {"frozen", "Frozen world"}, {"barren", "Barren world"},
				{"toxic", "Toxic world"}, {"volcanic", "Volcanic world"}, {"ocean", "Ocean world"}, {"desert", "Desert world"},
				{"crystal", "Crystal world"}}) {
			builder.add("archetype.starwake." + kind[0], kind[1]);
		}
		builder.add("item.starwake.survey_suit.oxygen", "Oxygen: %s:%s");
		builder.add("item.starwake.survey_suit.rating", "Rated %s to %s °C");
		builder.add("death.attack.starwake.hypoxia", "%1$s ran out of air");
		builder.add("death.attack.starwake.hypoxia.player", "%1$s ran out of air while fighting %2$s");
		builder.add("death.attack.starwake.toxic_atmosphere", "%1$s breathed a toxic atmosphere");
		builder.add("death.attack.starwake.toxic_atmosphere.player", "%1$s breathed a toxic atmosphere while fighting %2$s");
		builder.add("death.attack.starwake.extreme_heat", "%1$s was cooked by the planet's heat");
		builder.add("death.attack.starwake.extreme_heat.player", "%1$s was cooked by the planet's heat while fighting %2$s");
		builder.add("hud.starwake.env.breathable", "AIR OK");
		builder.add("hud.starwake.env.no_air", "NO AIR");
		builder.add("hud.starwake.env.toxic", "TOXIC %s%%");
		builder.add("hud.starwake.env.cabin", "CABIN PRESSURIZED");
		builder.add("hud.starwake.env.suit", "SUIT O2");
		builder.add("hud.starwake.env.exposed", "EXPOSED");
		builder.add("hud.starwake.env.suit_rating", "SUIT RATING EXCEEDED");
		builder.add("wristcom.home.environment", "Air: %s   Temp: %s °C");
		builder.add("wristcom.home.type", "Type: %s");
		builder.add(ModBlocks.HELM, "Helm");
		builder.add(ModBlocks.THRUSTER, "Thruster");
		builder.add(ModBlocks.MAIN_ENGINE, "Main Engine");
		builder.add(ModBlocks.WING, "Wing");
		builder.add(ModBlocks.LANDING_GEAR, "Landing Gear");
		builder.add(ModBlocks.MANEUVERING_THRUSTER, "Maneuvering Thruster");
		builder.add(ModBlocks.FUEL_TANK, "Fuel Tank");
		builder.add(ModBlocks.SEAT, "Seat");
		builder.add(ModBlocks.POWER_PLANT, "Power Plant");
		builder.add(ModBlocks.COOLER, "Cooler");
		builder.add(ModBlocks.SHIELD_GENERATOR, "Shield Generator");
		builder.add(ModBlocks.QUANTUM_DRIVE, "Quantum Drive");
		builder.add(ModBlocks.JUMP_DRIVE, "Jump Drive");
		// Pre-flight: components
		builder.add("preflight.starwake.no_power", "No power plant: nothing to run the engines");
		builder.add("preflight.starwake.power", "Power: %s plant(s), %s output");
		builder.add("preflight.starwake.cooling", "Cooling %s vs heat %s: plants at full output");
		builder.add("preflight.starwake.overheating", "Overheating: heat %s vs cooling %s, plants throttled to %s. Add coolers");
		builder.add("preflight.starwake.engines_underpowered", "Engines want %s power but get %s at the default split: thrust %s");
		builder.add("preflight.starwake.no_shields", "No shield generator: crashes hurt the crew");
		builder.add("preflight.starwake.shields", "Shields %s HP (regen %s at the default split)");
		builder.add("preflight.starwake.no_quantum", "No quantum drive: can't travel between planets");
		builder.add("preflight.starwake.quantum", "Quantum drive: spools in %ss at the default split");
		// Quantum travel
		builder.add("message.starwake.quantum.no_drive", "No quantum drive aboard");
		builder.add("message.starwake.quantum.no_power", "Route power to Quantum to spool the drive");
		builder.add("message.starwake.quantum.aborted", "Quantum travel aborted");
		// Power routing
		builder.add("screen.starwake.power.engines", "Engines: %s%%");
		builder.add("screen.starwake.power.shields", "Shields: %s%%");
		builder.add("screen.starwake.power.quantum", "Quantum: %s%%");
		builder.add("console.starwake.ship.power", "Power %s, cooling %s, engines %s, shield %s");
		builder.add("hud.starwake.decoupled", "DECOUPLED");
		builder.add("key.starwake.chase_camera", "Cockpit / Chase Camera");
		builder.add("subtitles.starwake.ship.engine_loop", "Ship engines roar");
		builder.add("subtitles.starwake.ship.thruster_loop", "Thrusters hiss");
		builder.add("subtitles.starwake.ship.rcs_puff", "Maneuvering thruster puffs");
		builder.add("subtitles.starwake.ship.quantum_spool", "Quantum drive spools");
		builder.add("subtitles.starwake.ship.quantum_engage", "Quantum drive engages");
		builder.add("subtitles.starwake.ship.quantum_travel_loop", "Quantum travel hums");
		builder.add("subtitles.starwake.ship.quantum_exit", "Ship drops out of quantum");
		builder.add("subtitles.starwake.ship.alarm", "Cockpit alarm");
		builder.add("subtitles.starwake.ship.gear", "Landing gear moves");
		builder.add("subtitles.starwake.ship.reentry_loop", "Re-entry roars");
		builder.add("hud.starwake.gear_up", "GEAR UP");
		builder.add("key.starwake.gear", "Landing Gear");
		builder.add("message.starwake.gear.down", "Landing gear down");
		builder.add("message.starwake.gear.up", "Landing gear up");
		builder.add("message.starwake.gear.blocked", "No room to lower the landing gear");
		builder.add("message.starwake.disassemble.gear_up", "Lower the landing gear before disassembling");
		builder.add("preflight.starwake.gear", "Landing gear: %s strut(s) (soft touchdowns up to ~6 m/s with gear down)");
		builder.add("preflight.starwake.no_gear", "No landing gear: every touchdown is a belly landing (crash damage above ~1.6 m/s)");
		builder.add("key.starwake.wristcom", "Wristcom");
		builder.add("screen.starwake.wristcom", "WRISTCOM");
		builder.add("screen.starwake.wristcom.hint", "G or Esc to close");
		builder.add("screen.starwake.wristcom.in_space_near", "Space, near %s");
		builder.add("wristcom.app.home", "Home");
		builder.add("wristcom.app.ship", "Ship");
		builder.add("wristcom.app.crew", "Crew");
		builder.add("wristcom.crew.owner", "Owner: %s");
		builder.add("wristcom.crew.locked", "LOCKED: crew only");
		builder.add("wristcom.crew.unlocked", "Unlocked: anyone may ride");
		builder.add("wristcom.crew.list", "Crew (may pilot)");
		builder.add("wristcom.crew.none", "No crew yet");
		builder.add("wristcom.crew.lock", "Lock ship");
		builder.add("wristcom.crew.unlock", "Unlock ship");
		builder.add("wristcom.crew.name", "Player name");
		builder.add("wristcom.crew.add", "Add crew");
		builder.add("wristcom.crew.owner_only", "Only the owner can change access.");
		builder.add("message.starwake.ship.not_yours", "This ship belongs to %s. Ask them to add you as crew");
		builder.add("message.starwake.ship.locked", "%s has locked this ship");
		builder.add("message.starwake.ship.owner_only", "Only the ship's owner can change its crew");
		builder.add("message.starwake.ship.crew_unknown", "No player named %s is online");
		builder.add("message.starwake.ship.crew_added", "%s is now crew");
		builder.add("wristcom.app.starmap", "Starmap");
		builder.add("wristcom.app.contracts", "Contracts");
		builder.add("wristcom.app.contracts.desc", "Jobs from faction contacts: deliveries, bounties, salvage. Accept, track and turn in contracts here.");
		builder.add("wristcom.app.trade", "Trade");
		builder.add("wristcom.app.trade.desc", "Commodity prices at known terminals, your best routes, and market history.");
		builder.add("wristcom.app.cargo", "Cargo");
		builder.add("wristcom.app.cargo.desc", "Your ship's hold and personal inventory: what you carry, its value and where it came from.");
		builder.add("wristcom.app.comms", "Comms");
		builder.add("wristcom.app.comms.desc", "Messages, hails and party chat with other pilots.");
		builder.add("wristcom.coming_soon", "Arrives with Milestone %s");
		builder.add("wristcom.home.hello", "Welcome back, %s");
		builder.add("wristcom.home.location", "Location: %s");
		builder.add("wristcom.home.gravity", "Gravity: %sg");
		builder.add("wristcom.home.no_ship", "Not aboard a ship");
		builder.add("wristcom.home.piloting", "Piloting: fuel %s/%s, shields %s");
		builder.add("wristcom.home.aboard", "Aboard: fuel %s/%s, shields %s");
		builder.add("wristcom.home.controls", "Flight controls");
		builder.add("wristcom.ship.none", "You're not aboard a ship. Use a ship's Helm to run its pre-flight check and assemble it.");
		builder.add("wristcom.ship.power", "Power routing");
		builder.add("wristcom.ship.report", "Ship report");
		builder.add("wristcom.ship.toggle_mode", "Coupled / Decoupled");
		builder.add("wristcom.ship.pilot_only", "Only the pilot can route power.");
		builder.add("wristcom.starmap.plot", "Plot QT: %s");
		builder.add("wristcom.starmap.you", "You");
		builder.add("wristcom.starmap.destinations", "Destinations");
		builder.add("wristcom.starmap.plot_hint", "Pilot a ship in space to plot");
		builder.add("hud.starwake.low_fuel", "LOW FUEL");
		builder.add("hud.starwake.overheat", "OVERHEAT");
		builder.add("hud.starwake.shields_down", "SHIELDS DOWN");
		builder.add("hud.starwake.qt.mode", "QT > %s");
		builder.add("hud.starwake.qt.travel", "QUANTUM > %s");
		builder.add("hud.starwake.qt.committed", "Committed: no steering");
		builder.add("hud.starwake.qt.no_target", "No target: aim at a marker");
		builder.add("hud.starwake.qt.too_close", "Too close: fly there");
		builder.add("hud.starwake.qt.obstructed", "Route obstructed");
		builder.add("hud.starwake.qt.no_fuel", "Not enough fuel");
		builder.add("hud.starwake.qt.spooling", "Spooling...");
		builder.add("hud.starwake.qt.align", "Align nose with marker");
		builder.add("hud.starwake.qt.ready", "READY: hold B");
		builder.add("key.starwake.quantum", "Quantum Drive (tap: mode, hold: engage)");
		builder.add("star.starwake.solace", "Solace");
		builder.add("message.starwake.quantum.on", "Quantum mode: aim at a marker, then hold B");
		builder.add("message.starwake.quantum.off", "Quantum mode off");
		builder.add("message.starwake.quantum.course", "Course set: line up on the marker and hold B");
		builder.add("message.starwake.quantum.no_target", "No quantum target: put the nose on a marker");
		builder.add("message.starwake.quantum.not_spooled", "Quantum drive still spooling");
		builder.add("message.starwake.quantum.too_close", "Too close to calibrate a jump");
		builder.add("message.starwake.quantum.obstructed", "Quantum route obstructed");
		builder.add("message.starwake.quantum.no_fuel", "Not enough fuel for this jump");
		builder.add("message.starwake.quantum.not_aligned", "Not aligned with the target");
		builder.add("message.starwake.quantum.engaged", "Quantum travel to %s: ETA %s");
		builder.add("message.starwake.quantum.arrived", "Dropped out of quantum near %s. Fly into the atmosphere to land");
		builder.add("console.starwake.ship.near", "Nearest: %s. Fly into a planet's atmosphere to land");
		builder.add("console.starwake.ship.route", "%s: %s, %s, fuel %s %s");
		builder.add("console.starwake.ship.route.too_close", "(here)");
		builder.add("console.starwake.ship.route.obstructed", "(obstructed)");
		builder.add("console.starwake.ship.route.no_fuel", "(low fuel)");
		builder.add("key.starwake.roll_left", "Roll Left");
		builder.add("key.starwake.roll_right", "Roll Right");
		builder.add("key.starwake.free_look", "Free Look (hold)");
		builder.add("key.starwake.leave_seat", "Leave Seat");
		builder.add("hud.starwake.ship6", "Fuel %s/%s  %s m/s  HDG %s°  PIT %s°  ROL %s°  %s");
		builder.add("hud.starwake.ship.alt", "ALT %s");
		builder.add("hud.starwake.ship.orbit", "Orbiting %s");
		builder.add("message.starwake.disassemble.not_level", "Level the ship (within 20 degrees) to disassemble");
		builder.add("message.starwake.disassemble.obstructed_rotation", "Not enough room to square the ship up for disassembly");

		// Fuel tank messages
		builder.add("message.starwake.fuel_tank.filled", "Loaded %s fuel (%s / %s)");
		builder.add("message.starwake.fuel_tank.full", "Tank full (%s / %s)");
		builder.add("message.starwake.fuel_tank.gauge", "Fuel: %s / %s");

		// Ship console
		builder.add("screen.starwake.ship_console", "Ship Console: Pre-flight Check");
		builder.add("screen.starwake.ship_status", "Ship Console: Status");
		builder.add("screen.starwake.ship_console.rescan", "Re-scan");
		builder.add("screen.starwake.ship_console.assemble", "Assemble Ship");
		builder.add("screen.starwake.ship_console.disassemble", "Disassemble");
		builder.add("message.starwake.assembled", "Ship assembled (%s blocks). Steer with the mouse, WASD/Space/Ctrl thrust, Z/C roll, Shift boost, Alt free look, X leave, R console");
		builder.add("message.starwake.disassembled", "Ship disassembled into blocks");
		builder.add("message.starwake.disassemble.failed", "Can't disassemble this ship");
		builder.add("message.starwake.disassemble.moving", "Bring the ship to a stop before disassembling");
		builder.add("message.starwake.disassemble.obstructed", "Can't disassemble: blocked at %s %s %s");
		builder.add("message.starwake.helm_taken", "Someone is already at the helm");
		builder.add("message.starwake.edit.hint", "Hold a block to build here (sneak to build against usable blocks)");
		builder.add("message.starwake.edit.not_allowed", "Only %s's crew can modify this ship");
		builder.add("message.starwake.edit.helm", "The Helm holds the ship together: disassemble to remove it");
		builder.add("message.starwake.edit.detached", "%s blocks broke away from the ship");
		builder.add("message.starwake.edit.unsupported", "That block can't be placed on a ship");
		builder.add("message.starwake.edit.too_big", "Ships are limited to %s blocks");
		builder.add("hud.starwake.ship", "Fuel %s / %s   Speed %s m/s   Alt %s");
		builder.add("hud.starwake.ship.space", "Fuel %s / %s   Speed %s m/s   Orbiting %s");
		builder.add("message.starwake.not_sealed_for_space", "Hull not sealed: this ship can't leave the atmosphere");
		builder.add("message.starwake.entered_space", "In orbit (%s). Fly back into the atmosphere to land, or press B for quantum mode");
		builder.add("message.starwake.entered_atmosphere", "Entering the atmosphere of %s");
		builder.add("message.starwake.course.not_in_space", "Courses can only be set from space");
		builder.add("message.starwake.course.unknown", "Unknown destination");
		builder.add("screen.starwake.ship_console.course", "QT: %s");
		builder.add("console.starwake.ship.sealed", "Cabin sealed: space-capable");
		builder.add("console.starwake.ship.unsealed", "Cabin not sealed: atmosphere only");
		builder.add("console.starwake.ship.to_space", "Climb above Y %s to reach space");
		builder.add("console.starwake.ship.mass", "%s blocks, %s t, %sg");
		builder.add("console.starwake.ship.fuel", "Fuel %s / %s");
		builder.add("console.starwake.ship.controls", "Mouse steers. WASD/Space/Ctrl thrust, Z/C roll, Shift boost, Alt free look, X leave seat. Engines hover automatically");
		builder.add("console.starwake.ship.can_disassemble", "Stationary: you can disassemble the ship back into blocks to edit it");
		builder.add("console.starwake.ship.moving", "Moving: stop to disassemble");
		builder.add("console.starwake.ship.space_no_disassemble", "Land on a planet to disassemble");
		builder.add("message.starwake.lost_in_space", "You drifted out of orbit and were brought home");
		builder.add("key.starwake.ship_console", "Ship Console");
		builder.add("key.category.starwake.starwake", "Starwake");
		builder.add("entity.starwake.ship", "Ship");
		builder.add("preflight.starwake.structure", "Structure: %s blocks, mass %s t");
		builder.add("preflight.starwake.too_big", "Structure exceeds %s blocks. Is the ship touching another build?");
		builder.add("preflight.starwake.multiple_helms", "%s Helms found: a ship can only have one");
		builder.add("preflight.starwake.no_pilot_room", "The Helm needs 2 free blocks above it for the pilot");
		builder.add("preflight.starwake.no_thrusters", "No thrusters");
		builder.add("preflight.starwake.lift_low", "Not enough lift: %s vs weight %s at %sg (need %s). Add downward-facing thrusters around the centre of mass, or lose mass");
		builder.add("preflight.starwake.lift_ok", "Lift %s vs weight %s at %sg (%sx)");
		builder.add("preflight.starwake.blocked_exhaust", "%s thruster(s) have blocked exhaust and won't fire");
		builder.add("preflight.starwake.handling", "Accel m/s²: fwd %s, back %s, left %s, right %s, up %s, down %s");
		builder.add("preflight.starwake.centre_of_mass", "Centre of mass: %s, %s, %s");
		builder.add("preflight.starwake.lift_unbalanced", "Lift is off-centre: only %s of %s lift is usable without tipping (weight %s). Spread lift thrusters around the centre of mass");
		builder.add("preflight.starwake.lift_wasted", "%s of lift is spent keeping level: lift thrusters are off-centre");
		builder.add("preflight.starwake.wings", "Wings: %s panels (%s lifting) carry %s of the weight at %s m/s here");
		builder.add("key.starwake.decouple", "Coupled / Decoupled");
		builder.add("message.starwake.decoupled", "DECOUPLED: no automatic braking");
		builder.add("message.starwake.coupled", "COUPLED: flight computer brakes drift");
		builder.add("commands.starwake.ship.blueprint", "Placed %s. Helm at %s");
		builder.add("preflight.starwake.turning", "Turning °/s²: pitch %s, yaw %s, roll %s");
		builder.add("preflight.starwake.cant_turn", "Can't %s: no thrusters can twist the ship that way. Put thrusters (maneuvering thrusters are best) far from the centre of mass, facing across the turn");
		builder.add("preflight.starwake.cant_move", "No thrust for: %s");
		builder.add("preflight.starwake.no_tanks", "No fuel tanks");
		builder.add("preflight.starwake.no_fuel", "Fuel tanks are empty (capacity %s). Load fuel into them");
		builder.add("preflight.starwake.fuel_ok", "Fuel %s / %s (about %ss at full burn)");
		builder.add("preflight.starwake.sealed", "Cabin sealed (%s blocks of air): space-capable");
		builder.add("preflight.starwake.not_sealed", "Cabin not sealed: air escapes near %s %s %s. Atmosphere-only");
		builder.add("preflight.starwake.verdict_space", "Ready for flight, including space");
		builder.add("preflight.starwake.verdict_atmo", "Ready for atmospheric flight (seal the cabin to reach space)");
		builder.add("preflight.starwake.verdict_grounded", "Grounded: fix the failed checks above");

		// Planets (names referenced from data/starwake/starwake/planet/*.json)
		builder.add("planet.starwake.veyra", "Veyra");
		builder.add("planet.starwake.overworld", "Overworld");
		builder.add("planet.starwake.space", "Deep Space");

		// Commands
		builder.add("commands.starwake.planet.unknown", "Unknown planet: %s");
		builder.add("commands.starwake.ship.not_helm", "That block isn't a Helm");
		builder.add("commands.starwake.ship.none_nearby", "No ship within 64 blocks");
		builder.add("commands.starwake.ship.drift", "Ship %s is drifting");
		builder.add("commands.starwake.ship.assembled", "Assembled ship %s at %s");
		builder.add("commands.starwake.planet.missing_dimension", "Planet %s has no loaded dimension");
		builder.add("commands.starwake.planet.list.header", "%s planet(s) charted:");
		builder.add("commands.starwake.planet.list.entry", " - %s (%s), gravity %sg");
		builder.add("commands.starwake.planet.tp.success", "Sent %s player(s) to %s");
		builder.add("commands.starwake.planet.leave.success", "Returned %s player(s) home");
	}

	/** "native_gold" -> "Native Gold". */
	private static String title(String id) {
		StringBuilder out = new StringBuilder();
		for (String part : id.split("_")) {
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return out.toString();
	}
}
