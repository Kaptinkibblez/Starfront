#!/usr/bin/env python3
"""Writes the Starwake modpack's quest book (Heracles / Odyssey Quests) into pack/config/heracles.

Usage: python3 tools/pack_quests.py [registry_items.txt]

Each chapter is a list of quests; a quest's position comes from its depth in the dependency tree. With
the item list (every registered item id, dumped from a server running the pack) every id is checked first.
"""
import json, os, re, shutil, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'pack', 'config', 'heracles')

# Numismatics coins: spur 1, bevel 8, sprocket 16, cog 64, crown 512, sun 4096.
def coins(n):
    out = []
    for coin, value in (('sun', 4096), ('crown', 512), ('cog', 64), ('sprocket', 16), ('bevel', 8), ('spur', 1)):
        if n >= value:
            out.append(('numismatics:' + coin, n // value))
            n %= value
    return out

# Quest: (id, title, icon, description, tasks, deps, rewards)
#   tasks: ('item', id, count) | ('tag', '#tag', count) | ('check',) | ('dim', dimension)
#   rewards: list of (item, count) plus an optional 'xp' level count and 'coins' value
def Q(qid, title, icon, desc, tasks, deps=(), items=(), xp=0, money=0, sub=''):
    return dict(id=qid, title=title, icon=icon, desc=desc, tasks=tasks, deps=list(deps), items=list(items),
                xp=xp, money=money, sub=sub)

def item(i, n=1): return ('item', i, n)
def tag(t, n=1): return ('tag', t, n)

CHAPTERS = []

def chapter(name, icon, quests):
    CHAPTERS.append((name, icon, quests))

# ---------------------------------------------------------------- 1. Welcome
chapter('1. Planetfall', 'minecraft:oak_sapling', [
    Q('welcome', 'Welcome to Starwake', 'heracles:quest_book', [
        'You have crash-landed on an untamed world with nothing but your hands.',
        'Starwake is an engineering pack built on Create: you will go from wooden shafts to brass factories, '
        'foundries, electric grids, trains, airships, warships and finally rockets that leave the planet.',
        'Open this book any time with U (or use the Quest Book item). Each chapter is one stage. Finish a quest to unlock the next ones and claim its rewards: '
        'experience and Numismatics coins, the currency used by shops and banks.',
        'Playing with friends? Every player has their own progress. Use Open Parties and Claims to team up and '
        'protect your base (default key: apostrophe).'],
      [('check',)], items=[('minecraft:bread', 8)], money=8),
    Q('wood', 'Timber!', 'minecraft:oak_log', ['Punch some trees. Create runs on wood and stone early on.'],
      [tag('#minecraft:logs', 32)], ['welcome'], items=[('minecraft:apple', 4)], xp=1),
    Q('stone', 'Stone Age', 'minecraft:cobblestone', ['Mine cobblestone for tools, furnaces and andesite.'],
      [item('minecraft:cobblestone', 64)], ['wood'], xp=1),
    Q('andesite', 'Andesite', 'minecraft:andesite',
      ['Andesite is the backbone of early Create. Find it underground or in large surface patches.'],
      [item('minecraft:andesite', 32)], ['stone'], money=4, xp=1),
    Q('iron', 'Iron Ingots', 'minecraft:iron_ingot', ['Smelt raw iron. You will need a lot of it.'],
      [item('minecraft:iron_ingot', 16)], ['stone'], money=8, xp=2),
    Q('copper', 'Copper', 'minecraft:copper_ingot', ['Copper becomes pipes, tanks, casings and later wire.'],
      [item('minecraft:copper_ingot', 16)], ['stone'], money=8, xp=2),
    Q('zinc', 'Zinc', 'create:raw_zinc',
      ['Zinc ore spawns deep below the surface (look below Y 70, mostly around Y 0-10 and in deepslate). '
       'Smelt it into ingots: together with copper it makes brass.'],
      [item('create:raw_zinc', 8)], ['iron', 'copper'], money=16, xp=2),
    Q('bed', 'A Place to Sleep', 'comforts:sleeping_bag_white',
      ['Skip the night in a bed, or carry a sleeping bag for long trips (it does not reset your spawn).'],
      [tag('#minecraft:beds', 1)], ['wood'], items=[('comforts:sleeping_bag_white', 1)], xp=1),
    Q('food', 'Something Hot', 'farmersdelight:cooking_pot',
      ["Farmer's Delight adds cooking. A Cooking Pot over a fire or Stove makes meals that keep you fed far longer."],
      [item('farmersdelight:cooking_pot', 1)], ['iron'], items=[('farmersdelight:tomato_seeds', 4), ('farmersdelight:cabbage_seeds', 4)], xp=2),
    Q('backpack', 'Pack Mule', 'sophisticatedbackpacks:backpack',
      ['Craft a backpack. Upgrade it later with iron, gold and diamonds, and add pickup and magnet upgrades.'],
      [item('sophisticatedbackpacks:backpack', 1)], ['wood'], money=8, xp=2),
    Q('waystone', 'Waystones', 'waystones:waystone',
      ['Waystones found in villages and the wild can be activated and used to travel between them. '
       'Craft your own to link your bases.'],
      [item('waystones:waystone', 1)], ['iron'], items=[('waystones:warp_dust', 4)], money=16, xp=3),
])

# ---------------------------------------------------------------- 2. Create: kinetics
chapter('2. First Rotations', 'create:cogwheel', [
    Q('alloy', 'Andesite Alloy', 'create:andesite_alloy',
      ['Combine andesite with iron nuggets (or zinc nuggets) to make Andesite Alloy, the basic Create material. '
       'In JEI, hover an item and press R for its recipe or U for its uses; hold W over a Create item to watch its Ponder scene.'],
      [item('create:andesite_alloy', 16)], [], money=8, xp=2),
    Q('shaft', 'Shafts and Cogs', 'create:cogwheel',
      ['Shafts carry rotation in a straight line; cogwheels pass it sideways. Large and small cogs change speed.'],
      [item('create:shaft', 8), item('create:cogwheel', 8), item('create:large_cogwheel', 2)], ['alloy'], money=8, xp=2),
    Q('power', 'First Power', 'create:water_wheel',
      ['Rotation has to come from somewhere: a Water Wheel in flowing water, a Windmill Bearing with sails, or '
       'a Hand Crank in a pinch. Every source gives stress units (SU); every machine uses them.'],
      [item('create:water_wheel', 1)], ['shaft'], money=16, xp=2),
    Q('goggles', "Engineer's Goggles", 'create:goggles',
      ["Wear goggles to read speed, stress and contents when you look at machines."],
      [item('create:goggles', 1)], ['shaft'], money=8, xp=1),
    Q('wrench', 'Wrench', 'create:wrench', ['Rotate and pick up Create blocks quickly.'],
      [item('create:wrench', 1)], ['shaft'], money=8, xp=1),
    Q('casing', 'Andesite Casing', 'create:andesite_casing',
      ['Apply andesite alloy to stripped logs to make casings: the frame of many machines.'],
      [item('create:andesite_casing', 16)], ['alloy'], money=16, xp=2),
    Q('press', 'Mechanical Press', 'create:mechanical_press',
      ['Presses flatten ingots into sheets. Iron sheets are everywhere in Create recipes.'],
      [item('create:mechanical_press', 1), item('create:iron_sheet', 16)], ['power', 'casing'], money=32, xp=3),
    Q('millstone', 'Millstone', 'create:millstone',
      ['Grind ores and gravel for extra output, and wheat into flour.'],
      [item('create:millstone', 1)], ['power'], money=16, xp=2),
    Q('belt', 'Conveyor Belts', 'create:belt_connector',
      ['Belts move items and connect two shafts. Put a Depot or a belt under a press to process items as they pass.'],
      [item('create:belt_connector', 8), item('create:depot', 2)], ['press'], money=16, xp=2),
    Q('fan', 'Encased Fan', 'create:encased_fan',
      ['Fans push items through fire (smelting), lava (blasting), water (washing) and soul fire (haunting). '
       'Bulk washing crushed ore gives bonus nuggets.'],
      [item('create:encased_fan', 2)], ['casing'], money=32, xp=3),
    Q('mixer', 'Mixer and Basin', 'create:mechanical_mixer',
      ['A Mechanical Mixer above a Basin makes alloys, doughs and many shapeless recipes.'],
      [item('create:mechanical_mixer', 1), item('create:basin', 2)], ['press'], money=32, xp=3),
    Q('crushing', 'Crushing Wheels', 'create:crushing_wheel',
      ['Two crushing wheels spinning toward each other turn one ore into crushed ore plus bonuses. '
       'Wash it with a fan for nuggets: much more metal per ore.'],
      [item('create:crushing_wheel', 2), tag('#create:crushed_raw_materials', 32)], ['millstone', 'fan'], money=64, xp=4),
    Q('drill', 'Drills and Saws', 'create:mechanical_drill',
      ['Drills and saws break blocks in front of them. Put them on pistons or bearings for quarries and tree farms.'],
      [item('create:mechanical_drill', 1), item('create:mechanical_saw', 1)], ['casing'], money=32, xp=3),
    Q('contraption', 'Contraptions', 'create:mechanical_piston',
      ['Pistons, bearings, gantries and pulleys move whole structures. Glue blocks together with Super Glue.'],
      [item('create:mechanical_piston', 1), item('create:super_glue', 1)], ['drill'], money=32, xp=3),
    Q('fluids', 'Pumps and Tanks', 'create:fluid_tank',
      ['Mechanical Pumps move fluids through pipes; Fluid Tanks stack into large tanks.'],
      [item('create:mechanical_pump', 1), item('create:fluid_pipe', 16), item('create:fluid_tank', 4)], ['mixer'], money=32, xp=3),
])

# ---------------------------------------------------------------- 3. Brass
chapter('3. The Brass Age', 'create:brass_ingot', [
    Q('burner', 'Blaze Burner', 'create:blaze_burner',
      ['Capture a blaze from the Nether in an Empty Blaze Burner. Heated basins mix brass and much more.'],
      [item('create:blaze_burner', 1)], [], money=64, xp=4),
    Q('brass', 'Brass', 'create:brass_ingot',
      ['Mix copper and zinc in a heated basin.'],
      [item('create:brass_ingot', 32)], ['burner'], money=64, xp=4),
    Q('tube', 'Electron Tubes', 'create:electron_tube',
      ['Polished rose quartz and iron sheets make electron tubes, used in all smart machines.'],
      [item('create:electron_tube', 8)], ['brass'], money=32, xp=3),
    Q('brass_casing', 'Brass Casing', 'create:brass_casing', ['The frame of advanced machines.'],
      [item('create:brass_casing', 16)], ['brass'], money=32, xp=3),
    Q('precision', 'Precision Mechanism', 'create:precision_mechanism',
      ['Your first sequenced assembly: press, deploy cogwheels, deploy shafts and add nuggets, repeated on a '
       'golden sheet. Use a Deployer and a belt with a press.'],
      [item('create:deployer', 1), item('create:precision_mechanism', 4)], ['tube', 'brass_casing'], money=128, xp=6),
    Q('crafter', 'Mechanical Crafters', 'create:mechanical_crafter',
      ['A grid of Mechanical Crafters crafts big recipes automatically.'],
      [item('create:mechanical_crafter', 9)], ['brass_casing'], money=64, xp=4),
    Q('arm', 'Mechanical Arm', 'create:mechanical_arm',
      ['Arms pick items up from one block and drop them into another. Set their targets by clicking with the arm.'],
      [item('create:mechanical_arm', 1)], ['precision'], money=64, xp=4),
    Q('rsc', 'Rotation Speed Controller', 'create:rotation_speed_controller',
      ['Set exact speeds for a whole network.'],
      [item('create:rotation_speed_controller', 1)], ['precision'], money=64, xp=4),
    Q('steam', 'Steam Engines', 'create:steam_engine',
      ['A fluid tank of water heated by blaze burners powers steam engines: the big power source of Create.'],
      [item('create:steam_engine', 2)], ['burner', 'brass_casing'], money=128, xp=6),
    Q('logistics', 'Smart Logistics', 'create:brass_funnel',
      ['Brass funnels, tunnels and smart chutes filter and split items.'],
      [item('create:brass_funnel', 4), item('create:brass_tunnel', 2), item('create:smart_chute', 1)], ['brass_casing'], money=64, xp=4),
    Q('packages', 'Packages and Frogports', 'create:packager',
      ['Create 6 logistics: Packagers box items, Frogports and chain conveyors deliver them, and a Stock Ticker '
       'orders from your whole storage network.'],
      [item('create:packager', 2), item('create:package_frogport', 2), item('create:stock_ticker', 1)], ['logistics', 'precision'],
      money=256, xp=8),
    Q('vault', 'Item Vaults', 'create:item_vault', ['Big, stackable storage that belts and funnels can use.'],
      [item('create:item_vault', 4)], ['brass'], money=32, xp=3),
    Q('enchant', 'Industrial Enchanting', 'create_enchantment_industry:blaze_enchanter',
      ['Create: Enchantment Industry turns liquid experience into enchantments. Drain XP with a Mechanical '
       'Grindstone and feed it to a Blaze Enchanter.'],
      [item('create_enchantment_industry:blaze_enchanter', 1)], ['steam'], money=128, xp=5),
    Q('chunkload', 'Keep It Running', 'create_power_loader:andesite_chunk_loader',
      ['Chunk loaders keep your factory running while you are away (they need rotation).'],
      [item('create_power_loader:andesite_chunk_loader', 1)], ['brass'], money=64, xp=3),
])

# ---------------------------------------------------------------- 4. Metallurgy
chapter('4. Foundry and Metallurgy', 'createmetallurgy:steel_ingot', [
    Q('dusts', 'Ore Dusts', 'createmetallurgy:dirty_iron_dust',
      ['Create: Metallurgy grinds crushed ore into dirty dusts, washed into clean dusts that melt efficiently.'],
      [item('createmetallurgy:dirty_iron_dust', 8)], [], money=32, xp=3),
    Q('mortar', 'Refractory Mortar', 'createmetallurgy:refractory_mortar',
      ['Furnace linings must survive molten metal. Mix refractory mortar for the foundry.'],
      [item('createmetallurgy:refractory_mortar', 8)], [], money=32, xp=3),
    Q('foundry', 'The Foundry', 'createmetallurgy:foundry_basin',
      ['A Foundry Basin with a Foundry Lid over a heated burner melts metals into fluids.'],
      [item('createmetallurgy:foundry_basin', 1), item('createmetallurgy:foundry_lid', 1)], ['mortar'], money=128, xp=5),
    Q('graphite', 'Graphite Molds', 'createmetallurgy:graphite_ingot_mold',
      ['Graphite molds shape molten metal into ingots, plates, rods, gears and nuggets.'],
      [item('createmetallurgy:graphite', 4), item('createmetallurgy:graphite_ingot_mold', 1)], ['foundry'], money=64, xp=3),
    Q('casting', 'Casting', 'createmetallurgy:casting_table',
      ['Pour molten metal with a Faucet onto a Casting Table (ingots, plates) or into a Casting Basin (blocks).'],
      [item('createmetallurgy:casting_table', 1), item('createmetallurgy:casting_basin', 1), item('createmetallurgy:faucet', 1)],
      ['graphite'], money=64, xp=4),
    Q('coke', 'Coke', 'createmetallurgy:coke', ['Coke is pure carbon: the reducing agent for steel.'],
      [item('createmetallurgy:coke', 16)], ['foundry'], money=32, xp=3),
    Q('steel', 'Steel', 'createmetallurgy:steel_ingot',
      ['Alloy molten iron with coke in a superheated foundry. Steel is the material of cannons, rails and ships.'],
      [item('createmetallurgy:steel_ingot', 32)], ['coke', 'casting'], money=256, xp=8),
    Q('tungsten', 'Wolframite', 'createmetallurgy:raw_tungsten',
      ['Wolframite ore holds tungsten, the metal with the highest melting point. It needs the hottest foundry.'],
      [item('createmetallurgy:raw_tungsten', 8)], ['foundry'], money=64, xp=4),
    Q('tungsten_ingot', 'Tungsten', 'createmetallurgy:tungsten_ingot',
      ['Refine wolframite into tungsten ingots and wire (used by advanced electrics).'],
      [item('createmetallurgy:tungsten_ingot', 8)], ['tungsten', 'steel'], money=256, xp=8),
    Q('crucible', 'Industrial Crucible', 'createmetallurgy:industrial_crucible',
      ['A larger, sturdier melter for serious production.'],
      [item('createmetallurgy:industrial_crucible', 1)], ['steel'], money=256, xp=6),
    Q('grinder', 'Belt Grinder', 'createmetallurgy:mechanical_belt_grinder',
      ['Polish and finish parts on a Mechanical Belt Grinder.'],
      [item('createmetallurgy:mechanical_belt_grinder', 1)], ['steel'], money=128, xp=4),
])

# ---------------------------------------------------------------- 5. Electricity
chapter('5. Electricity', 'createaddition:electric_motor', [
    Q('rods', 'Rods and Wire', 'createaddition:copper_wire',
      ["Create Crafts & Additions' Rolling Mill turns ingots into rods and wire."],
      [item('createaddition:rolling_mill', 1), item('createaddition:copper_wire', 16)], [], money=64, xp=4),
    Q('spool', 'Wire Spools', 'createaddition:copper_spool', ['Spools connect power connectors over distance.'],
      [item('createaddition:copper_spool', 2)], ['rods'], money=32, xp=3),
    Q('alternator', 'Alternator', 'createaddition:alternator', ['Turns rotation into electricity (FE).'],
      [item('createaddition:alternator', 1)], ['rods'], money=128, xp=5),
    Q('connector', 'Power Lines', 'createaddition:connector',
      ['Place connectors and link them with a spool to run power lines.'],
      [item('createaddition:connector', 4), item('createaddition:large_connector', 2)], ['spool'], money=64, xp=3),
    Q('accumulator', 'Accumulators', 'createaddition:modular_accumulator', ['Store electricity for later.'],
      [item('createaddition:modular_accumulator', 4)], ['alternator'], money=128, xp=4),
    Q('motor', 'Electric Motor', 'createaddition:electric_motor',
      ['Turn electricity back into rotation anywhere: no shafts across the map.'],
      [item('createaddition:electric_motor', 2)], ['alternator', 'connector'], money=256, xp=6),
    Q('tesla', 'Tesla Coil', 'createaddition:tesla_coil', ['Charge items wirelessly and make charged materials.'],
      [item('createaddition:tesla_coil', 1)], ['accumulator'], money=128, xp=4),
    Q('biofuel', 'Biofuel', 'createaddition:bioethanol_bucket',
      ['Ferment biomass into bioethanol: a liquid fuel for blaze burners and engines.'],
      [item('createaddition:bioethanol_bucket', 1)], ['motor'], money=128, xp=5),
])

# ---------------------------------------------------------------- 6. Trains
chapter('6. Rails and Logistics', 'create:track_station', [
    Q('track', 'Train Tracks', 'create:track', ['Lay track with right-click drags; it curves and slopes smoothly.'],
      [item('create:track', 64)], [], money=64, xp=3),
    Q('station', 'Stations', 'create:track_station',
      ['A Train Station assembles trains and is a stop for schedules.'],
      [item('create:track_station', 2)], ['track'], money=64, xp=3),
    Q('controls', 'Train Controls', 'create:controls',
      ['Build a train on the track next to a station: a frame of railway casing on bogeys, with Train Controls.'],
      [item('create:controls', 1), item('create:railway_casing', 8)], ['station'], money=128, xp=5),
    Q('signal', 'Signals', 'create:track_signal', ['Signals keep trains from crashing into each other.'],
      [item('create:track_signal', 4)], ['station'], money=64, xp=3),
    Q('schedule', 'Schedules', 'create:schedule',
      ['Give a train a schedule and it runs on its own, loading and unloading cargo at stations.'],
      [item('create:schedule', 1)], ['controls'], money=64, xp=4),
    Q('conductor', 'Conductor', 'railways:white_conductor_cap',
      ["Steam 'n' Rails adds conductors, more tracks, semaphores, couplers and smokestacks."],
      [tag('#railways:conductor_caps', 1)], ['controls'], money=64, xp=3),
    Q('fuel', 'Fuel Tanks', 'railways:fuel_tank', ['Fuel tanks let a train burn liquid fuel.'],
      [item('railways:fuel_tank', 2)], ['controls'], money=64, xp=3),
    Q('handcar', 'Handcar', 'railways:handcar', ['A cheap human-powered rail vehicle.'],
      [item('railways:handcar', 1)], ['track'], money=32, xp=2),
])

# ---------------------------------------------------------------- 7. Economy
chapter('7. Trade and Coin', 'numismatics:crown', [
    Q('coins', 'Coinage', 'numismatics:cog',
      ['Numismatics coins: Spur (1), Bevel (8), Sprocket (16), Cog (64), Crown (512) and Sun (4096). '
       'Quests pay in coins. Combine or split them in a crafting grid.'],
      [item('numismatics:cog', 1)], [], money=64, xp=2),
    Q('card', 'Bank Account', 'numismatics:blue_card',
      ['Craft a bank card and bind it at a Bank Terminal to keep coins safely on account.'],
      [tag('#numismatics:cards', 1)], ['coins'], money=32, xp=2),
    Q('terminal', 'Bank Terminal', 'numismatics:bank_terminal', ['Deposit and withdraw coins from your account.'],
      [item('numismatics:bank_terminal', 1)], ['card'], money=64, xp=3),
    Q('depositor', 'Depositors', 'numismatics:andesite_depositor',
      ['Depositors take coins (or a card) to emit a redstone pulse: pay-to-use doors, rides and machines.'],
      [item('numismatics:andesite_depositor', 1)], ['coins'], money=64, xp=3),
    Q('vendor', 'Open a Shop', 'numismatics:vendor',
      ['Vendors sell items for coins while you are away. On a server, trade with your friends.'],
      [item('numismatics:vendor', 1)], ['terminal'], money=128, xp=4),
    Q('banker', 'Blaze Banker', 'numismatics:blaze_banker',
      ['A Blaze Banker lets machines spend from an account: pay for train tickets or shop orders automatically.'],
      [item('numismatics:blaze_banker', 1)], ['vendor'], money=256, xp=5),
    Q('rich', 'Crown Jewels', 'numismatics:crown', ['Save up 8 Crowns (4096 spurs).'],
      [item('numismatics:crown', 8)], ['vendor'], items=[('numismatics:sun', 1)], xp=10),
])

# ---------------------------------------------------------------- 8. Aeronautics
chapter('8. Taking Flight', 'aeronautics:wooden_propeller', [
    Q('assembler', 'Physics Assembler', 'simulated:physics_assembler',
      ['Create Aeronautics turns anything you build into a real physics object. Place a Physics Assembler on '
       'your build and power it with redstone to make it a moving "sub-level".'],
      [item('simulated:physics_assembler', 1)], [], money=128, xp=4),
    Q('levitite', 'Levitite', 'aeronautics:levitite',
      ['Levitite is a buoyant block: enough of it makes a structure float. Mix Levitite Blend and let it set.'],
      [item('aeronautics:levitite', 8)], ['assembler'], money=128, xp=4),
    Q('balloon', 'Hot Air', 'aeronautics:white_envelope',
      ['Hot air envelopes over a Hot Air Burner lift a ship. The burner controls how much.'],
      [item('aeronautics:white_envelope', 16), item('aeronautics:adjustable_burner', 1)], ['assembler'], money=128, xp=5),
    Q('propeller', 'Propellers', 'aeronautics:wooden_propeller',
      ['A Propeller Bearing with a propeller pushes your ship. Drive it with rotation from an engine aboard.'],
      [item('aeronautics:propeller_bearing', 2), item('aeronautics:wooden_propeller', 2)], ['assembler'], money=128, xp=5),
    Q('engine', 'Portable Engine', 'simulated:white_portable_engine',
      ['Portable engines burn fuel to make rotation aboard a ship.'],
      [item('simulated:white_portable_engine', 1)], ['propeller'], money=128, xp=4),
    Q('helm', 'Steering', 'simulated:steering_wheel',
      ['Steering Wheels and Throttle Levers give you a proper cockpit.'],
      [item('simulated:steering_wheel', 1), item('simulated:throttle_lever', 1)], ['propeller'], money=128, xp=4),
    Q('first_flight', 'First Flight', 'aeronautics:aviators_goggles',
      ['Build a ship that floats, steers and flies. Wear Aviator\'s Goggles for the full look. '
       'Click the checkmark when you have flown your first ship.'],
      [('check',), item('aeronautics:aviators_goggles', 1)], ['levitite', 'balloon', 'engine', 'helm'], money=512, xp=10),
    Q('smart_prop', 'Smart Propellers', 'aeronautics:smart_propeller',
      ['Smart Propellers and the Gyroscopic Propeller Bearing keep a ship level and let it hover.'],
      [item('aeronautics:smart_propeller', 2), item('aeronautics:gyroscopic_propeller_bearing', 1)], ['first_flight'], money=256, xp=6),
    Q('sensors', 'Avionics', 'simulated:altitude_sensor',
      ['Altitude, velocity and gimbal sensors output redstone for autopilots.'],
      [item('simulated:altitude_sensor', 1), item('simulated:velocity_sensor', 1), item('simulated:gimbal_sensor', 1)],
      ['first_flight'], money=256, xp=6),
    Q('dock', 'Docking', 'simulated:docking_connector', ['Docking connectors join ships and stations together.'],
      [item('simulated:docking_connector', 2)], ['first_flight'], money=128, xp=4),
    Q('wheels', 'Ground Vehicles', 'offroad:wheel_mount',
      ['Wheel Mounts and tires make cars, rovers and trucks.'],
      [item('offroad:wheel_mount', 4), item('offroad:tire', 4)], ['assembler'], money=128, xp=4),
])

# ---------------------------------------------------------------- 9. Cannons
chapter('9. Big Guns', 'createbigcannons:cannon_mount', [
    Q('powder', 'Powder Charges', 'createbigcannons:powder_charge',
      ['Create Big Cannons fire real shells propelled by powder charges.'],
      [item('createbigcannons:powder_charge', 8)], [], money=64, xp=3),
    Q('cast', 'Cannon Casting', 'createbigcannons:small_cast_mould',
      ['Cannon parts are cast: place a cast mould, surround it with Casting Sand and pour in molten cast iron, '
       'bronze or steel. The Foundry from chapter 4 can supply the metal.'],
      [item('createbigcannons:small_cast_mould', 1), item('createbigcannons:casting_sand', 16)], [], money=128, xp=4),
    Q('barrel', 'Barrel and Chamber', 'createbigcannons:cast_iron_cannon_barrel',
      ['Cast parts come out unbored: bore them on a Cannon Drill. A cannon is a chamber, barrels and a breech or end.'],
      [item('createbigcannons:cast_iron_cannon_barrel', 2), item('createbigcannons:cast_iron_cannon_chamber', 1)], ['cast'], money=128, xp=5),
    Q('mount', 'Cannon Mount', 'createbigcannons:cannon_mount',
      ['Mount the cannon to aim it with rotation; a Fixed Cannon Mount points it along a ship.'],
      [item('createbigcannons:cannon_mount', 1)], ['barrel'], money=128, xp=5),
    Q('shot', 'Ammunition', 'createbigcannons:solid_shot',
      ['Solid shot punches through armour; HE shells explode. Shells need fuzes.'],
      [item('createbigcannons:solid_shot', 8), item('createbigcannons:he_shell', 4), item('createbigcannons:impact_fuze', 4)],
      ['powder'], money=128, xp=5),
    Q('fire', 'Fire!', 'minecraft:tnt',
      ['Load powder and a shell into the breech (or use a Cannon Loader) and fire with redstone. '
       'Click the checkmark when your cannon has fired.'],
      [('check',)], ['mount', 'shot'], money=256, xp=8),
    Q('loader', 'Cannon Loader', 'createbigcannons:cannon_loader', ['Load cannons automatically.'],
      [item('createbigcannons:cannon_loader', 1)], ['fire'], money=128, xp=4),
    Q('autocannon', 'Autocannon', 'createbigcannons:cast_iron_autocannon_barrel',
      ['Autocannons fire small rounds quickly: the gun for airships.'],
      [item('createbigcannons:cast_iron_autocannon_barrel', 2), item('createbigcannons:autocannon_cartridge', 16)], ['fire'], money=256, xp=6),
    Q('steel_cannon', 'Built-Up Steel', 'createbigcannons:built_up_steel_cannon_barrel',
      ['Steel cannons are built up from layers and survive far bigger charges.'],
      [item('createbigcannons:steel_cannon_barrel', 2)], ['fire'], money=512, xp=8),
    Q('warship', 'Warship', 'createbigcannons:fixed_cannon_mount',
      ['Put guns on an Aeronautics ship (chapter 8). Click the checkmark once your armed ship has flown.'],
      [item('createbigcannons:fixed_cannon_mount', 1), ('check',)], ['autocannon'], money=1024, xp=12),
])

# ---------------------------------------------------------------- 10. Space
chapter('10. To the Stars', 'rocketnautics:rocket_thruster', [
    Q('titanium', 'Titanium', 'rocketnautics:raw_titanium',
      ['Create Cosmonautics adds titanium ore deep underground. It is the metal of rockets.'],
      [item('rocketnautics:raw_titanium', 16)], [], money=128, xp=5),
    Q('ti_ingot', 'Titanium Ingots', 'rocketnautics:titanium_ingot', ['Process and smelt titanium.'],
      [item('rocketnautics:titanium_ingot', 16), item('rocketnautics:titanium_sheet', 8)], ['titanium'], money=256, xp=6),
    Q('ti_alloy', 'Titanium Alloy', 'rocketnautics:titanium_alloy', ['A lighter, stronger alloy for engines and hulls.'],
      [item('rocketnautics:titanium_alloy', 16)], ['ti_ingot'], money=512, xp=8),
    Q('casing', 'Resilient Casing', 'rocketnautics:titanium_casing', ['The casing of rocket machinery.'],
      [item('rocketnautics:titanium_casing', 8)], ['ti_ingot'], money=256, xp=5),
    Q('thruster', 'Rocket Thruster', 'rocketnautics:rocket_thruster',
      ['A rocket thruster with a nozzle, fed with fuel through hoses (anchored with Hose Anchors), lifts a ship off the ground.'],
      [item('rocketnautics:rocket_thruster', 1), item('rocketnautics:engine_nozzle', 1), item('rocketnautics:hose_anchor', 2)],
      ['ti_alloy', 'casing'], money=1024, xp=10),
    Q('rcs', 'Attitude Control', 'rocketnautics:rcs_thruster',
      ['RCS thrusters and vector thrusters turn the rocket in flight; a Magnetic Stabilizer holds it steady.'],
      [item('rocketnautics:rcs_thruster', 4), item('rocketnautics:magnetic_stabilizer', 1)], ['thruster'], money=512, xp=8),
    Q('staging', 'Staging', 'rocketnautics:separator',
      ['Stage separators drop spent boosters.'],
      [item('rocketnautics:separator', 1), item('rocketnautics:booster_thruster', 2)], ['thruster'], money=512, xp=8),
    Q('suit', 'Space Suit', 'rocketnautics:space_helmet',
      ['You need a Space Helmet to breathe outside the atmosphere. A jetpack helps on EVAs.'],
      [item('rocketnautics:space_helmet', 1), item('rocketnautics:jetpack', 1)], ['ti_alloy'], money=512, xp=8),
    Q('sputnik', 'Sputnik', 'rocketnautics:sputnik',
      ['The Sputnik probe flies a rocket by program (visual or Lua). Use it for autopilots and orbits.'],
      [item('rocketnautics:sputnik', 1)], ['rcs'], money=512, xp=8),
    Q('orbit', 'Reach Space', 'rocketnautics:hologram_block',
      ['Fly a sealed rocket high enough to reach the space dimension. Click the checkmark once you have been '
       'in orbit.'],
      [('check',)], ['rcs', 'staging', 'suit'], money=2048, xp=15),
    Q('moon', 'One Small Step', 'rocketnautics:lunar_regolith',
      ['Land on the Moon and bring back lunar regolith.'],
      [item('rocketnautics:lunar_regolith', 16)], ['orbit'], items=[('numismatics:sun', 1)], xp=20),
    Q('moonbase', 'Moon Base', 'rocketnautics:lunar_regolith_brick',
      ['Build an outpost on the Moon. Lunar regolith bricks make good walls.'],
      [item('rocketnautics:lunar_regolith_brick', 64)], ['moon'], money=2048, xp=15),
])

# ---------------------------------------------------------------- 11. Farming and comfort
chapter("11. Farmer's Delight", 'farmersdelight:tomato', [
    Q('knife', 'Kitchen Knife', 'farmersdelight:iron_knife', ['Knives harvest straw and cut ingredients on a Cutting Board.'],
      [tag('#c:tools/knife', 1)], [], money=16, xp=1),
    Q('board', 'Cutting Board', 'farmersdelight:cutting_board', ['Slice food, logs and more on a cutting board.'],
      [item('farmersdelight:cutting_board', 1)], ['knife'], money=16, xp=1),
    Q('stove', 'Stove', 'farmersdelight:stove', ['A stove heats pots and skillets without a campfire.'],
      [item('farmersdelight:stove', 1)], [], money=16, xp=2),
    Q('crops', 'New Crops', 'farmersdelight:cabbage',
      ['Grow tomatoes, cabbages, onions and rice (found wild, or from the seeds of chapter 1).'],
      [item('farmersdelight:tomato', 8), item('farmersdelight:cabbage', 8), item('farmersdelight:onion', 8), item('farmersdelight:rice', 8)],
      [], money=32, xp=2),
    Q('meal', 'A Proper Meal', 'farmersdelight:fried_rice', ['Cook a meal in the cooking pot.'],
      [item('farmersdelight:fried_rice', 4)], ['crops', 'stove'], money=32, xp=3),
    Q('feast', 'Feast', 'farmersdelight:roast_chicken_block', ['Cook a feast to share with your crew.'],
      [item('farmersdelight:roast_chicken_block', 1)], ['meal'], money=64, xp=4),
    Q('harvester', 'Automated Farm', 'create:mechanical_harvester',
      ['Mechanical Harvesters and Ploughs on a moving contraption tend fields for you.'],
      [item('create:mechanical_harvester', 2), item('create:mechanical_plough', 1)], ['crops'], money=64, xp=3),
])

# ---------------------------------------------------------------- 12. Multiplayer
chapter('12. Crew and Colonies', 'minecraft:emerald', [
    Q('party', 'Form a Crew', 'minecraft:player_head',
      ['Open the Open Parties and Claims menu (default key: apostrophe) to create a party and invite friends. '
       'Click the checkmark when you have a party (or are playing solo by choice).'],
      [('check',)], [], money=32, xp=2),
    Q('claim', 'Stake a Claim', 'minecraft:oak_sign',
      ['Claim your base chunks on the map (Open Parties and Claims, or the world map with M) so nobody can '
       'grief it. Claims can be shared with your party.'],
      [('check',)], ['party'], money=64, xp=2),
    Q('storage', 'Shared Storage', 'sophisticatedstorage:chest',
      ['Sophisticated Storage chests and barrels upgrade in place and connect with a Controller.'],
      [item('sophisticatedstorage:chest', 4), item('sophisticatedstorage:controller', 1)], ['party'], money=64, xp=3),
    Q('voice', 'Voice Chat', 'minecraft:note_block',
      ['Simple Voice Chat is included: press V for settings and talk to players near you, or create a group. '
       'Click the checkmark to continue.'],
      [('check',)], ['party'], money=16, xp=1),
    Q('compass', 'Explorer', 'explorerscompass:explorerscompass',
      ["The Explorer's Compass finds structures: villages, ruins and Create's world features."],
      [item('explorerscompass:explorerscompass', 1)], ['claim'], money=64, xp=3),
    Q('nether', 'Into the Nether', 'minecraft:obsidian', ['Visit the Nether. Blaze burners need blazes.'],
      [('dim', 'minecraft:the_nether')], ['claim'], money=64, xp=4),
    Q('end', 'The End', 'minecraft:end_stone', ['Find a stronghold and reach the End.'],
      [('dim', 'minecraft:the_end')], ['nether'], money=1024, xp=15),
])


def task_json(i, task, title):
    kind = task[0]
    if kind in ('item', 'tag'):
        return {'type': 'heracles:item', 'item': task[1], 'amount': task[2], 'collection': 'AUTOMATIC'}
    if kind == 'check':
        return {'type': 'heracles:check', 'title': 'Done'}
    if kind == 'dim':
        return {'type': 'heracles:changed_dimension', 'to': task[1]}
    raise ValueError(kind)


def layout(quests):
    by_id = {q['id']: q for q in quests}
    depth = {}
    def d(qid):
        if qid not in depth:
            deps = [x for x in by_id[qid]['deps'] if x in by_id]
            depth[qid] = 0 if not deps else 1 + max(d(x) for x in deps)
        return depth[qid]
    rows = {}
    pos = {}
    for q in quests:
        c = d(q['id'])
        r = rows.get(c, 0)
        rows[c] = r + 1
        pos[q['id']] = (c, r)
    out = {}
    for qid, (c, r) in pos.items():
        n = rows[c]
        out[qid] = [c * 44, int((r - (n - 1) / 2) * 34)]
    return out


def validate(registry_file):
    """registry_file: every item id of the pack, one per line (dumped from a running server)."""
    known = set(open(registry_file).read().split())
    missing = []
    def check(i):
        if not i.startswith('#') and i not in known:
            missing.append(i)
    for _, icon, quests in CHAPTERS:
        check(icon)
        for q in quests:
            check(q['icon'])
            for t in q['tasks']:
                if t[0] == 'item':
                    check(t[1])
            for i, _ in q['items'] + coins(q['money']):
                check(i)
    if missing:
        sys.exit('unknown ids: ' + ', '.join(sorted(set(missing))))


def main():
    if len(sys.argv) == 2:
        validate(sys.argv[1])
    if os.path.isdir(ROOT):
        shutil.rmtree(ROOT)
    os.makedirs(os.path.join(ROOT, 'quests'))
    groups, settings, seen = [], {}, set()
    for name, icon, quests in CHAPTERS:
        groups.append(name)
        settings[name] = {'icon': icon, 'iconEnabled': True, 'background': '', 'backgroundOpacity': 100}
        folder = re.sub(r'[^a-z0-9]', '', name.lower())
        os.makedirs(os.path.join(ROOT, 'quests', folder))
        pos = layout(quests)
        prefix = folder + '_'
        for q in quests:
            qid = prefix + q['id']
            assert qid not in seen, qid
            seen.add(qid)
            rewards = {}
            for n, (i, c) in enumerate(q['items'] + coins(q['money'])):
                rewards[f'item_{n}'] = {'type': 'heracles:item', 'item': {'id': i, 'count': c}}
            if q['xp']:
                rewards['xp'] = {'type': 'heracles:xp', 'xptype': 'LEVEL', 'amount': q['xp']}
            data = {
                'display': {
                    'icon': {'type': 'heracles:item', 'item': {'id': q['icon'], 'count': 1}},
                    'title': q['title'],
                    'subtitle': q['sub'],
                    'description': [line for p in q['desc'] for line in (p, '')][:-1],
                    'groups': {name: {'position': pos[q['id']]}},
                },
                'settings': {'individual_progress': False, 'hidden': 'LOCKED', 'unlockNotification': True,
                             'showDependencyArrow': True, 'repeatable': False, 'autoClaimRewards': False},
                'dependencies': [prefix + x for x in q['deps']],
                'tasks': {f'task_{n}': task_json(n, t, q['title']) for n, t in enumerate(q['tasks'])},
                'rewards': rewards,
            }
            with open(os.path.join(ROOT, 'quests', folder, qid + '.json'), 'w') as f:
                json.dump(data, f, indent=2)
    # Chapters after the first unlock from the previous chapter's key quest.
    gates = {'2. First Rotations': ('1planetfall_andesite', '1planetfall_iron'),
             '3. The Brass Age': ('2firstrotations_mixer',),
             '4. Foundry and Metallurgy': ('3thebrassage_brass',),
             '5. Electricity': ('3thebrassage_brass',),
             '6. Rails and Logistics': ('3thebrassage_precision',),
             '7. Trade and Coin': ('1planetfall_welcome',),
             '8. Taking Flight': ('3thebrassage_precision',),
             '9. Big Guns': ('4foundryandmetallurgy_casting',),
             '10. To the Stars': ('8takingflight_first_flight', '4foundryandmetallurgy_steel'),
             "11. Farmer's Delight": ('1planetfall_food',),
             '12. Crew and Colonies': ('1planetfall_welcome',)}
    for name, icon, quests in CHAPTERS:
        if name not in gates:
            continue
        folder = re.sub(r'[^a-z0-9]', '', name.lower())
        for q in quests:
            if not q['deps']:
                path = os.path.join(ROOT, 'quests', folder, folder + '_' + q['id'] + '.json')
                data = json.load(open(path))
                data['dependencies'] = list(gates[name])
                json.dump(data, open(path, 'w'), indent=2)
    with open(os.path.join(ROOT, 'groups.txt'), 'w') as f:
        f.write('\n'.join(groups) + '\n')
    with open(os.path.join(ROOT, 'group_settings.json'), 'w') as f:
        json.dump(settings, f, indent=2)
    print(f'{len(seen)} quests in {len(groups)} chapters')


if __name__ == '__main__':
    main()
