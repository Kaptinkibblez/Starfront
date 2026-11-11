package com.starwake.planet.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A small planet plant (tuft grass, puffballs): a cross-model plant that grows on any solid
 * ground, since alien soils aren't vanilla dirt. Planets generate them with
 * {@code random_patch}-style features.
 */
public class PlanetPlantBlock extends VegetationBlock {
	private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 13, 14);

	public PlanetPlantBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
		return state.isFaceSturdy(level, pos, Direction.UP) && !state.is(net.minecraft.world.level.block.Blocks.BARRIER);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE.move(state.getOffset(pos));
	}
}
