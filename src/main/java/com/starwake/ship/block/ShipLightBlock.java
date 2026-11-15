package com.starwake.ship.block;

import com.starwake.ship.panel.LightType;
import com.starwake.ship.panel.PanelGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Ship Light (M9 pass 3): strip, panel, running, flood and landing lights and the navigation beacon, in five lens colours.
 * Use with an empty hand to switch it on or off; the beacon flashes (3 ticks on, 27 off) while on. The Hull Tool cycles
 * the colour and turns it. Light reaches the world where the block really is; on an assembled ship the lens still glows
 * (ships don't carry block light with them yet).
 */
public class ShipLightBlock extends Block implements ShapedBlock {
	public static final EnumProperty<LightType> TYPE = EnumProperty.create("type", LightType.class);
	public static final EnumProperty<LightType.Color> COLOR = EnumProperty.create("color", LightType.Color.class);
	
	public static final BooleanProperty ON = BooleanProperty.create("on");
	/** The beacon's flash phase (always true for steady lights). */
	public static final BooleanProperty FLASH = BooleanProperty.create("flash");

	public ShipLightBlock(Properties properties) {
		super(properties.lightLevel(ShipLightBlock::lightLevel));
		registerDefaultState(defaultBlockState().setValue(TYPE, LightType.STRIP).setValue(COLOR, LightType.Color.WHITE)
				.setValue(ORIENT, PanelGeometry.orient(Direction.UP, Direction.NORTH)).setValue(ON, true).setValue(FLASH, true));
	}

	public static boolean lit(BlockState state) {
		return state.getValue(ON) && state.getValue(FLASH);
	}

	private static int lightLevel(BlockState state) {
		return lit(state) ? state.getValue(TYPE).level() : 0;
	}

	@Override
	public com.starwake.ship.panel.PanelGeometry.Shape shapeOf(BlockState state) {
		return state.getValue(TYPE);
	}

	@Override
	public int orientOf(BlockState state) {
		return state.getValue(ORIENT);
	}

	@Override
	public boolean seals(BlockState state) {
		return false;
	}

	/** The lens shows the light's colour while lit (emissive, see {@link #emissive}), dim glass otherwise. */
	@Override
	public String texture(BlockState state, String key) {
		if (!key.equals("lens")) return ShapedBlock.super.texture(state, key);
		return "block/ship_light_" + (lit(state) ? state.getValue(COLOR).getSerializedName() : "off");
	}

	@Override
	public boolean emissive(BlockState state, String key) {
		return key.equals("lens") && lit(state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(TYPE, COLOR, ORIENT, ON, FLASH);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(ORIENT, PanelGeometry.placement(context));
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean movedByPiston) {
		if (state.getValue(TYPE) == LightType.BEACON && !old.is(this)) level.scheduleTick(pos, this, 20);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (state.getValue(TYPE) != LightType.BEACON) return;
		boolean flash = !state.getValue(FLASH);
		level.setBlock(pos, state.setValue(FLASH, flash), Block.UPDATE_CLIENTS);
		level.scheduleTick(pos, this, flash ? 3 : 27);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide()) {
			BlockState next = state.setValue(ON, !state.getValue(ON)).setValue(FLASH, true);
			level.setBlock(pos, next, Block.UPDATE_ALL);
			level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.4f, next.getValue(ON) ? 1.4f : 1.0f);
			if (next.getValue(TYPE) == LightType.BEACON) level.scheduleTick(pos, this, 20);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.shape(state.getValue(TYPE), state.getValue(ORIENT));
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(ORIENT, PanelGeometry.rotated(state.getValue(ORIENT), rotation));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return rotate(state, mirror.getRotation(PanelGeometry.front(state.getValue(ORIENT))));
	}
}
