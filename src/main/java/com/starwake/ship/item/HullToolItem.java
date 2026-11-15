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
		if (state.getBlock() instanceof com.starwake.ship.block.HullFormBlock) {
			if (!level.isClientSide()) shapeForm(context, level, pos, state);
			return InteractionResult.SUCCESS;
		}
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

	/**
	 * Hull Forms (M9 pass 4): the click picks a corner (near one), an edge (near its middle) or the whole top; use raises
	 * it an eighth, sneak-use lowers it. Forms around that share a moved corner (same facing) move with it.
	 */
	private static void shapeForm(UseOnContext context, Level level, BlockPos pos, BlockState state) {
		int orient = com.starwake.ship.block.HullFormBlock.orient(state);
		var hit = context.getClickLocation();
		var m = PanelGeometry.inverse(orient, hit.x - pos.getX(), hit.y - pos.getY(), hit.z - pos.getZ());
		double u = Math.max(0, Math.min(1, m.x)), v = Math.max(0, Math.min(1, m.z));
		double[][] corner = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
		java.util.List<Integer> targets = new java.util.ArrayList<>();
		for (int c = 0; c < 4; c++) if (Math.hypot(u - corner[c][0], v - corner[c][1]) < 0.3) targets.add(c);
		if (targets.isEmpty()) {
			double[] edgeDist = {v, 1 - u, 1 - v, u}; // edge k joins corners k and k+1
			int best = 0;
			for (int k = 1; k < 4; k++) if (edgeDist[k] < edgeDist[best]) best = k;
			if (edgeDist[best] < 0.2) {
				targets.add(best);
				targets.add((best + 1) % 4);
			} else {
				for (int c = 0; c < 4; c++) targets.add(c);
			}
		}
		Player player = context.getPlayer();
		int delta = player != null && player.isSecondaryUseActive() ? -1 : 1;
		java.util.Map<BlockPos, BlockState> changes = new java.util.LinkedHashMap<>();
		changes.put(pos, state);
		var facing = state.getValue(com.starwake.ship.block.HullFormBlock.FACING);
		int newHeight = 0;
		for (int c : targets) {
			int h = Math.max(0, Math.min(com.starwake.ship.panel.FormShape.STEPS, state.getValue(com.starwake.ship.block.HullFormBlock.CORNER[c]) + delta));
			newHeight = h;
			int[] o = com.starwake.ship.block.HullFormBlock.cornerOffset(state, c);
			// Every form whose boundary holds this corner's column: the four cells around it across the facing axis.
			for (int da = -1; da <= 0; da++) {
				for (int db = -1; db <= 0; db++) {
					int dx = 0, dy = 0, dz = 0;
					switch (facing.getAxis()) {
						case X -> { dy = o[1] + da; dz = o[2] + db; }
						case Y -> { dx = o[0] + da; dz = o[2] + db; }
						case Z -> { dx = o[0] + da; dy = o[1] + db; }
					}
					BlockPos other = pos.offset(dx, dy, dz);
					BlockState current = changes.containsKey(other) ? changes.get(other) : level.getBlockState(other);
					if (!(current.getBlock() instanceof com.starwake.ship.block.HullFormBlock)
							|| current.getValue(com.starwake.ship.block.HullFormBlock.FACING) != facing) continue;
					int k = com.starwake.ship.block.HullFormBlock.cornerAt(current, o[0] - dx, o[1] - dy, o[2] - dz);
					if (k < 0) continue;
					BlockState next = current.setValue(com.starwake.ship.block.HullFormBlock.CORNER[k], h);
					if (com.starwake.ship.block.HullFormBlock.volume(next) <= 1.0 / 4096 && isFlat(next)) continue; // keep something
					changes.put(other.immutable(), next);
				}
			}
		}
		for (var e : changes.entrySet()) {
			if (e.getValue() != level.getBlockState(e.getKey())) level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
		}
		level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.35f, 1.4f + 0.05f * newHeight);
		if (player != null) {
			player.sendOverlayMessage(Component.translatable("message.starwake.hull_tool.form",
					Component.translatable("message.starwake.hull_tool.form." + (targets.size() == 1 ? "corner" : targets.size() == 2 ? "edge" : "top")),
					newHeight));
		}
	}

	private static boolean isFlat(BlockState state) {
		for (var p : com.starwake.ship.block.HullFormBlock.CORNER) if (state.getValue(p) > 0) return false;
		return true;
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
