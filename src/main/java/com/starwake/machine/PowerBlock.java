package com.starwake.machine;

import com.starwake.registry.ModMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Solar Panel and Battery blocks (M8b); use one to read its output or charge. See {@link PowerBlockEntity}. */
public class PowerBlock extends Block implements EntityBlock {
	private static final VoxelShape PANEL = Block.box(0, 0, 0, 16, 6, 16);
	private final boolean solar;

	public PowerBlock(boolean solar, Properties properties) {
		super(properties);
		this.solar = solar;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new PowerBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() || (type != ModMachines.SOLAR_PANEL_BE && type != ModMachines.BATTERY_BE) ? null
				: (l, p, s, be) -> PowerBlockEntity.serverTick(l, p, s, (PowerBlockEntity) be);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return solar ? PANEL : super.getShape(state, level, pos, context);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof PowerBlockEntity be) {
			PowerNetworks.Network net = PowerNetworks.networkOf(server, pos);
			Component grid = Component.translatable("message.starwake.grid", String.format("%.1f", EnergyNode.kw(net.lastGeneration)),
					String.format("%.1f", EnergyNode.kw(net.lastDemand)), net.nodeCount(), net.cableCount());
			if (be.solar()) {
				player.sendOverlayMessage(Component.translatable("message.starwake.solar", String.format("%.2f", be.outputKw()),
						Math.round(be.sun() * 100), String.format("%.2f", be.starlight())).append("  ").append(grid));
			} else {
				double kwh = be.energyStored() / 3.6e6, cap = be.energyCapacity() / 3.6e6;
				player.sendOverlayMessage(Component.translatable("message.starwake.battery", String.format("%.2f", kwh),
						String.format("%.1f", cap), Math.round(cap <= 0 ? 0 : 100 * kwh / cap)).append("  ").append(grid));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return !solar;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		return level.getBlockEntity(pos) instanceof PowerBlockEntity be && be.energyCapacity() > 0
				? (int) (15 * be.energyStored() / be.energyCapacity()) : 0;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock())) PowerNetworks.invalidate(level, pos);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		PowerNetworks.invalidate(level, pos);
	}
}
