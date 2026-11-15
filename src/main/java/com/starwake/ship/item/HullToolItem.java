package com.starwake.ship.item;

import com.starwake.ship.block.GreebleBlock;
import com.starwake.ship.block.HullPieceBlock;
import com.starwake.ship.block.ProfileHullBlock;
import com.starwake.ship.block.ShapedBlock;
import com.starwake.ship.block.ShipLightBlock;
import com.starwake.ship.panel.PanelGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hull Tool (M9 pass 3/4): adjusts shaped hull blocks, on the ground or aboard an assembled ship (the use runs in the
 * ship's shipyard plot like any other item). Use: turn the piece a quarter about its up axis (greebles: next detail;
 * lights: next colour; pillars: next axis). Sneak-use: flip it upside down (the same front). Auto-connecting pieces and
 * their neighbours re-fit themselves after every change.
 */
public class HullToolItem extends Item {
	public HullToolItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ShapedBlock)) return InteractionResult.PASS;
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		Player player = context.getPlayer();
		boolean sneak = player != null && player.isSecondaryUseActive();
		BlockState next = state;
		Component message = null;
		if (state.getBlock() instanceof HullPieceBlock.Pillar) {
			Direction.Axis axis = state.getValue(HullPieceBlock.Pillar.AXIS);
			next = state.setValue(HullPieceBlock.Pillar.AXIS, Direction.Axis.values()[(axis.ordinal() + 1) % 3]);
		} else if (!sneak && state.getBlock() instanceof GreebleBlock) {
			var target = state.getValue(GreebleBlock.SHAPE).next(1);
			next = state.setValue(GreebleBlock.SHAPE, target);
			message = Component.translatable("message.starwake.hull_tool.shape", Component.translatable("greeble.starwake." + target.getSerializedName()));
		} else if (!sneak && state.getBlock() instanceof ShipLightBlock) {
			var target = state.getValue(ShipLightBlock.COLOR).next();
			next = state.setValue(ShipLightBlock.COLOR, target);
			message = Component.translatable("message.starwake.hull_tool.color", Component.translatable("light.starwake.color." + target.getSerializedName()));
		} else if (state.hasProperty(ShapedBlock.ORIENT)) {
			int orient = state.getValue(ShapedBlock.ORIENT);
			if (sneak && !(state.getBlock() instanceof GreebleBlock) && !(state.getBlock() instanceof ShipLightBlock)) {
				orient = PanelGeometry.orient(PanelGeometry.up(orient).getOpposite(), PanelGeometry.front(orient));
			} else {
				orient = PanelGeometry.turned(orient, 1);
			}
			next = state.setValue(ShapedBlock.ORIENT, orient);
		}
		if (next == state) return InteractionResult.PASS;
		if (next.getBlock() instanceof ProfileHullBlock profile) next = profile.connect(next, level, pos);
		level.setBlock(pos, next, Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.4f, 1.6f);
		if (player != null && message != null) player.sendOverlayMessage(message);
		return InteractionResult.SUCCESS;
	}
}
