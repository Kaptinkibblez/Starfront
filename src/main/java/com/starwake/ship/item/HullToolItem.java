package com.starwake.ship.item;

import com.starwake.ship.block.HullPanelBlock;
import com.starwake.ship.panel.PanelGeometry;
import com.starwake.ship.panel.PanelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hull Tool (M9 pass 3): reshapes placed Hull Panels (and greebles: next detail; lights: next colour), on the ground or aboard an assembled ship (the use runs in the
 * ship's shipyard plot like any other item). Use on a panel: the next shape of the tool's family (a panel of another
 * family first jumps to that family). Sneak-use: turn it a quarter about its up axis. Use in the air: switch the
 * tool's family (slopes, curves, plates, frames).
 */
public class HullToolItem extends Item {
	public HullToolItem(Properties properties) {
		super(properties);
	}

	public static PanelShape.Family family(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		int i = data == null ? 0 : data.copyTag().getIntOr("family", 0);
		PanelShape.Family[] all = PanelShape.Family.values();
		return all[Math.floorMod(i, all.length)];
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		PanelShape.Family next = PanelShape.Family.values()[(family(stack).ordinal() + 1) % PanelShape.Family.values().length];
		if (!level.isClientSide()) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("family", next.ordinal());
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			player.sendOverlayMessage(Component.translatable("message.starwake.hull_tool.family", familyName(next)));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		boolean greeble = state.getBlock() instanceof com.starwake.ship.block.GreebleBlock;
		boolean light = state.getBlock() instanceof com.starwake.ship.block.ShipLightBlock;
		if (!(state.getBlock() instanceof HullPanelBlock) && !greeble && !light) return InteractionResult.PASS;
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		Player player = context.getPlayer();
		BlockState next;
		if (player != null && player.isSecondaryUseActive()) {
			next = state.setValue(HullPanelBlock.ORIENT, PanelGeometry.turned(state.getValue(HullPanelBlock.ORIENT), 1));
		} else if (greeble) {
			var target = state.getValue(com.starwake.ship.block.GreebleBlock.SHAPE).next(1);
			next = state.setValue(com.starwake.ship.block.GreebleBlock.SHAPE, target);
			if (player != null) player.sendOverlayMessage(Component.translatable("message.starwake.hull_tool.shape",
					Component.translatable("greeble.starwake." + target.getSerializedName())));
		} else if (light) {
			var target = state.getValue(com.starwake.ship.block.ShipLightBlock.COLOR).next();
			next = state.setValue(com.starwake.ship.block.ShipLightBlock.COLOR, target);
			if (player != null) player.sendOverlayMessage(Component.translatable("message.starwake.hull_tool.color",
					Component.translatable("light.starwake.color." + target.getSerializedName())));
		} else {
			PanelShape shape = state.getValue(HullPanelBlock.SHAPE);
			PanelShape.Family want = family(context.getItemInHand());
			PanelShape target = shape.family() == want ? shape.next(1) : first(want);
			next = state.setValue(HullPanelBlock.SHAPE, target);
			if (player != null) player.sendOverlayMessage(Component.translatable("message.starwake.hull_tool.shape", shapeName(target)));
		}
		level.setBlock(pos, next, Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.4f, 1.6f);
		return InteractionResult.SUCCESS;
	}

	private static PanelShape first(PanelShape.Family family) {
		for (PanelShape s : PanelShape.values()) if (s.family() == family) return s;
		return PanelShape.SLOPE;
	}

	public static Component shapeName(PanelShape shape) {
		return Component.translatable("shape.starwake." + shape.getSerializedName());
	}

	public static Component familyName(PanelShape.Family family) {
		return Component.translatable("shape.starwake.family." + family.name().toLowerCase(java.util.Locale.ROOT));
	}
}
