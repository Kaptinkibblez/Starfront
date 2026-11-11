package com.starwake.ship.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Landing gear (3j). Mount it under the hull. DEPLOYED: a strut with a foot pad that reaches
 * half a block below its own block, so a landed ship stands on its gear with engines clear of
 * the ground. Retracted: a flush hatch. The ship toggles every gear block at once (N), and
 * touchdowns are only soft with the gear down (see ShipEntity.onImpact).
 */
public class LandingGearBlock extends Block {
	public static final BooleanProperty DEPLOYED = BooleanProperty.create("deployed");
	private static final VoxelShape DOWN = Shapes.or(Block.box(6, -8, 6, 10, 16, 10), Block.box(3, -8, 3, 13, -6, 13));
	private static final VoxelShape UP = Block.box(2, 12, 2, 14, 16, 14);

	public LandingGearBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(DEPLOYED, true));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(DEPLOYED);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(DEPLOYED) ? DOWN : UP;
	}
}
