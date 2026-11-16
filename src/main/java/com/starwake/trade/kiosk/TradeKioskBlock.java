package com.starwake.trade.kiosk;

import com.starwake.network.KioskPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Trade Kiosk (M9 player shops): place it at your base or aboard your ship, stock it (sneak-use: a one-row chest) and set
 * prices (use: the kiosk screen). Anyone can buy from it, even while the owner is offline; the credits go straight to
 * the owner. Only the owner (or a creative player) can break it.
 */
public class TradeKioskBlock extends HorizontalDirectionalBlock implements EntityBlock {
	public TradeKioskBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TradeKioskBlockEntity(pos, state);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (placer instanceof Player player && level.getBlockEntity(pos) instanceof TradeKioskBlockEntity kiosk) {
			kiosk.setOwner(player.getUUID(), player.getGameProfile().name());
		}
	}

	@Override
	protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof TradeKioskBlockEntity kiosk && kiosk.owner() != null && !kiosk.owner().equals(player.getUUID())
				&& !player.isCreative()) {
			return 0;
		}
		return super.getDestroyProgress(state, player, level, pos);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof TradeKioskBlockEntity kiosk)) return InteractionResult.SUCCESS;
		if (kiosk.owner() == null) kiosk.setOwner(sp.getUUID(), sp.getGameProfile().name());
		boolean owner = kiosk.owner().equals(sp.getUUID());
		if (owner && sp.isShiftKeyDown()) {
			sp.openMenu(kiosk);
			return InteractionResult.SUCCESS;
		}
		ServerPlayNetworking.send(sp, payload(kiosk, sp));
		return InteractionResult.SUCCESS;
	}

	static KioskPayload payload(TradeKioskBlockEntity kiosk, ServerPlayer viewer) {
		List<KioskPayload.Line> lines = new ArrayList<>();
		for (int i = 0; i < TradeKioskBlockEntity.SLOTS; i++) {
			ItemStack s = kiosk.getItem(i);
			if (!s.isEmpty()) lines.add(new KioskPayload.Line(i, s.copy(), kiosk.price(i)));
		}
		return new KioskPayload(kiosk.getBlockPos(), kiosk.ownerName(), viewer.getUUID().equals(kiosk.owner()),
				com.starwake.trade.Economy.credits(viewer), lines);
	}

	/** Server: a kiosk action from the screen. */
	public static void act(ServerPlayer player, KioskPayload.Action a) {
		Level level = com.starwake.ship.yard.Shipyard.inYard(a.pos()) ? com.starwake.ship.yard.Shipyard.level(player.level().getServer()) : player.level();
		if (level == null || !(level.getBlockEntity(a.pos()) instanceof TradeKioskBlockEntity kiosk)) return;
		if (level == player.level() && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(a.pos())) > 64) return;
		boolean owner = player.getUUID().equals(kiosk.owner());
		int slot = a.slot();
		if (slot < 0 || slot >= TradeKioskBlockEntity.SLOTS) return;
		switch (a.action()) {
			case "price" -> {
				if (owner) kiosk.setPrice(slot, a.value());
			}
			case "buy" -> {
				ItemStack stock = kiosk.getItem(slot);
				long price = kiosk.price(slot);
				int n = (int) Math.min(a.value(), stock.getCount());
				if (n <= 0 || price <= 0 || owner) break;
				long total = price * n;
				if (!com.starwake.trade.Economy.spend(player, total)) {
					player.sendOverlayMessage(Component.translatable("message.starwake.trade.no_credits", total));
					break;
				}
				ItemStack bought = stock.split(n);
				Component name = bought.getHoverName(); // before the inventory merges (and empties) the stack
				kiosk.setChanged();
				if (!player.getInventory().add(bought)) player.drop(bought, false, net.minecraft.util.Prediction.PREDICTED);
				if (kiosk.owner() != null) com.starwake.trade.Economy.payTo(player.level().getServer(), kiosk.owner(), total);
				player.sendOverlayMessage(Component.translatable("message.starwake.trade.bought", n, name, total));
				ServerPlayer seller = kiosk.owner() == null ? null : player.level().getServer().getPlayerList().getPlayer(kiosk.owner());
				if (seller != null) {
					seller.sendSystemMessage(Component.translatable("message.starwake.kiosk.sold", player.getName(), n, name, total));
				}
			}
			default -> {}
		}
		ServerPlayNetworking.send(player, payload(kiosk, player));
	}
}
