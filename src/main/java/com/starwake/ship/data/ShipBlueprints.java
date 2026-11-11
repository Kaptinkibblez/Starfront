package com.starwake.ship.data;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.ship.block.FuelTankBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Ship blueprints: ready-made designs as datapack JSON at
 * {@code data/<namespace>/starwake/blueprint/<id>.json}, placed with
 * {@code /starwake ship blueprint <id> <pos>} (nose to the north, min corner at pos). For test
 * flights with realistic ships, and later as starter ships / shop hulls.
 *
 * <pre>{@code
 * { "name": "Kestrel",
 *   "palette": { "H": "starwake:hull_plating", "E": "starwake:main_engine[facing=south]" },
 *   "layers": [ [ "..H..", ".HEH." ], ... ] }   // bottom layer first; rows north to south; chars west to east
 * }</pre>
 * '.' and ' ' are air (left untouched). Fuel tanks are filled to capacity.
 */
public final class ShipBlueprints {
	public record Blueprint(Optional<Component> name, Map<String, String> palette, List<List<String>> layers) {
		public static final Codec<Blueprint> CODEC = RecordCodecBuilder.create(i -> i.group(
				ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Blueprint::name),
				Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("palette").forGetter(Blueprint::palette),
				Codec.STRING.listOf().listOf().fieldOf("layers").forGetter(Blueprint::layers)
		).apply(i, Blueprint::new));
	}

	private static Map<Identifier, Blueprint> blueprints = Map.of();

	private ShipBlueprints() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("ship_blueprints"),
				new SimpleJsonResourceReloadListener<>(Blueprint.CODEC, FileToIdConverter.json("starwake/blueprint")) {
					@Override
					protected void apply(Map<Identifier, Blueprint> loaded, ResourceManager manager, ProfilerFiller profiler) {
						blueprints = Map.copyOf(loaded);
					}
				});
	}

	public static Map<Identifier, Blueprint> all() {
		return blueprints;
	}

	/**
	 * Places a blueprint with its min corner at {@code origin}.
	 *
	 * @return the Helm's position (null if the blueprint has none)
	 */
	public static @Nullable BlockPos place(ServerLevel level, Blueprint blueprint, BlockPos origin) throws CommandSyntaxException {
		HolderLookup<Block> blocks = level.holderLookup(Registries.BLOCK);
		Map<Character, BlockState> palette = new java.util.HashMap<>();
		for (var entry : blueprint.palette().entrySet()) {
			if (entry.getKey().length() != 1) continue;
			palette.put(entry.getKey().charAt(0), BlockStateParser.parseForBlock(blocks, entry.getValue(), false).blockState());
		}
		BlockPos helm = null;
		for (int y = 0; y < blueprint.layers().size(); y++) {
			List<String> rows = blueprint.layers().get(y);
			for (int z = 0; z < rows.size(); z++) {
				String row = rows.get(z);
				for (int x = 0; x < row.length(); x++) {
					BlockState state = palette.get(row.charAt(x));
					if (state == null) continue; // '.', ' ' and unknown characters: leave as is
					BlockPos pos = origin.offset(x, y, z);
					level.setBlock(pos, state, Block.UPDATE_CLIENTS);
					if (state.getBlock() instanceof com.starwake.ship.block.HelmBlock) helm = pos;
					if (level.getBlockEntity(pos) instanceof FuelTankBlockEntity tank) tank.refill(tank.capacity());
				}
			}
		}
		return helm;
	}

	/** Display name: the blueprint's own, or its id. */
	public static Component name(Identifier id, Blueprint blueprint) {
		return blueprint.name().orElse(Component.literal(id.toString()));
	}

	/** Air helper for callers that want to clear a blueprint's footprint first. */
	public static BlockState air() {
		return Blocks.AIR.defaultBlockState();
	}
}
