package com.starwake.ship.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A ship gun (M11): Ballistic Cannon or Laser Repeater, Mk I-III. FACING is where the barrel points (out of the face you
 * clicked); {@link #GIMBAL} = a gimballed mount that tracks the pilot's aim within the weapon's cone (weaker), else a
 * fixed mount that fires along FACING (stronger). The Hull Tool toggles the mount. Numbers come from
 * {@code ship_weapon/<block>.json} ({@link com.starwake.ship.weapon.WeaponSpec}); mass, power draw and armour from
 * ship_stats like any part.
 */
public class ShipWeaponBlock extends DirectionalBlock implements net.minecraft.world.level.block.EntityBlock {
	public static final BooleanProperty GIMBAL = BooleanProperty.create("gimbal");

	public ShipWeaponBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(GIMBAL, true));
	}

	@Override
	public net.minecraft.world.level.block.entity.@org.jspecify.annotations.Nullable BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos, BlockState state) {
		return new com.starwake.fab.MaterialBlockEntity(pos, state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, GIMBAL);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}
}
