package com.starwake.logistics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.machine.MachineSpec;
import com.starwake.registry.ModMachines;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A belt's cargo (M9, after Mekanism's logistical transporters and Create's belts): up to {@link #MAX} item stacks,
 * each with its progress along the belt (0 = entry, 1 = exit) and, on splitters and filters, the side it leaves by.
 * Items move {@code rate} blocks per second, queue behind each other ({@link #GAP}) and wait at the end until the
 * block ahead takes them. Hand-offs go through Fabric's {@code ItemStorage}: belts expose an insertion-only storage per
 * side, so belts, machines, chests, hoppers and other mods' inventories all connect the same way.
 *
 * <p>Both sides advance progress the same way; only the server hands items on, and every change (an item in or out)
 * re-sends the cargo, so clients draw it moving smoothly between updates.
 */
public class ConveyorBlockEntity extends BlockEntity {
	public static final int MAX = 4;
	public static final double GAP = 0.25;

	/** One stack on the belt. {@code exit} = the side it leaves by (splitter/filter), -1 = straight on. */
	public static final class Entry {
		public ItemStack stack;
		public double progress, previous;
		public int exit;

		Entry(ItemStack stack, double progress, int exit) {
			this.stack = stack;
			this.progress = progress;
			this.previous = progress;
			this.exit = exit;
		}

		Entry copy() {
			Entry e = new Entry(stack.copy(), progress, exit);
			e.previous = previous;
			return e;
		}

		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.CODEC.fieldOf("stack").forGetter(e -> e.stack),
				Codec.DOUBLE.fieldOf("progress").forGetter(e -> e.progress),
				Codec.INT.optionalFieldOf("exit", -1).forGetter(e -> e.exit)).apply(i, Entry::new));
	}

	/** Furthest along first. */
	private List<Entry> items = new ArrayList<>();
	private ItemStack filter = ItemStack.EMPTY;
	private int nextExit;
	private boolean left;
	/** Blocks per tick: from the machine spec on the server (specs are server data), synced to clients. */
	private double speed = 1.5 / 20;
	private final Participant participant = new Participant();

	public ConveyorBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.CONVEYOR_BE, pos, state);
	}

	public List<Entry> items() {
		return items;
	}

	public ItemStack filter() {
		return filter;
	}

	public void setFilter(ItemStack stack) {
		filter = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
		changed();
	}

	/** Filters: matching items leave to the left instead of the right. */
	public boolean left() {
		return left;
	}

	public void setLeft(boolean left) {
		this.left = left;
		changed();
	}

	private ConveyorBlock.Kind kind() {
		return getBlockState().getBlock() instanceof ConveyorBlock b ? b.kind : ConveyorBlock.Kind.BELT;
	}

	private Direction facing() {
		return getBlockState().getValue(ConveyorBlock.FACING);
	}

	// --- Ticking ----------------------------------------------------------------------------------------

	public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity belt) {
		belt.advance();
		if (level instanceof ServerLevel server) belt.handOff(server);
	}

	/** Moves every item along, queued behind the one ahead. */
	private void advance() {
		if (level != null && !level.isClientSide()) speed = MachineSpec.of(getBlockState().getBlock()).rate() / 20.0;
		double limit = 1.0;
		for (Entry e : items) {
			e.previous = e.progress;
			e.progress = Math.max(e.progress, Math.min(e.progress + speed, limit));
			limit = e.progress - GAP;
		}
	}

	/** Gives the front item to whatever is ahead once it reaches the end. */
	private void handOff(ServerLevel level) {
		if (items.isEmpty() || items.getFirst().progress < 1.0) return;
		Entry front = items.getFirst();
		Direction out = exitDirection(front);
		BlockPos target = ConveyorBlock.outputPos(getBlockState(), worldPosition, out);
		Direction side = out.getOpposite();
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, target, side);
		if (storage == null && kind() == ConveyorBlock.Kind.BELT) {
			// A decline one step down continues the line.
			BlockState below = level.getBlockState(target.below());
			if (below.getBlock() instanceof ConveyorBlock b && b.kind == ConveyorBlock.Kind.DECLINE && below.getValue(ConveyorBlock.FACING) == out) {
				storage = ItemStorage.SIDED.find(level, target.below(), side);
			}
		}
		if (storage == null) {
			// A splitter whose chosen side lost its receiver picks again.
			if (kind() == ConveyorBlock.Kind.SPLITTER) {
				int again = chooseExit(front.stack);
				if (again != front.exit) {
					front.exit = again;
					changed();
				}
			}
			return;
		}
		try (Transaction tx = Transaction.openOuter()) {
			long moved = storage.insert(ItemVariant.of(front.stack), front.stack.getCount(), tx);
			if (moved <= 0) return;
			tx.commit();
			front.stack.shrink((int) moved);
		}
		if (front.stack.isEmpty()) items.removeFirst();
		changed();
	}

	/** The side an item leaves by: straight on, or the side a splitter/filter chose. */
	private Direction exitDirection(Entry e) {
		if (kind() == ConveyorBlock.Kind.LIFT) {
			// Up the column while there's more lift above; the top one hands out toward FACING.
			return level != null && level.getBlockState(worldPosition.above()).getBlock() instanceof ConveyorBlock b && b.kind == ConveyorBlock.Kind.LIFT
					? Direction.UP : facing();
		}
		return e.exit >= 0 ? Direction.from3DDataValue(e.exit) : facing();
	}

	// --- Receiving ------------------------------------------------------------------------------------------

	/** Where an item coming in from {@code side} starts, or NaN if this belt doesn't take items from there. */
	private double entryProgress(@Nullable Direction side) {
		Direction f = facing();
		ConveyorBlock.Kind kind = kind();
		if (side == null) return 0.5; // dropped on top
		return switch (kind) {
			case BELT -> side == f.getOpposite() ? 0 : side.getAxis().isHorizontal() && side != f ? 0.5 : side == Direction.UP ? 0.5 : Double.NaN;
			case SPLITTER, FILTER, INCLINE, DECLINE -> side == f.getOpposite() ? 0 : Double.NaN;
			case LIFT -> side == Direction.DOWN || side.getAxis().isHorizontal() && side != f ? 0 : Double.NaN;
		};
	}

	/** Whether a stack may enter at {@code p}: room on the belt and clear of the other items. */
	private boolean room(double p) {
		if (items.size() >= MAX) return false;
		for (Entry e : items) if (Math.abs(e.progress - p) < GAP - 1e-6) return false;
		return true;
	}

	private void place(Entry e) {
		int i = 0;
		while (i < items.size() && items.get(i).progress >= e.progress) i++;
		items.add(i, e);
	}

	/** The exit a splitter or filter assigns to a new item (server side). */
	private int chooseExit(ItemStack stack) {
		Direction f = facing();
		return switch (kind()) {
			case FILTER -> !filter.isEmpty() && ItemStack.isSameItem(filter, stack)
					? (left ? f.getCounterClockWise() : f.getClockWise()).get3DDataValue() : -1;
			case SPLITTER -> {
				Direction[] exits = {f, f.getCounterClockWise(), f.getClockWise()};
				for (int k = 0; k < 3; k++) {
					Direction d = exits[(nextExit + k) % 3];
					if (level != null && hasReceiver(d)) {
						nextExit = (nextExit + k + 1) % 3;
						yield d == f ? -1 : d.get3DDataValue();
					}
				}
				yield -1;
			}
			default -> -1;
		};
	}

	private boolean hasReceiver(Direction d) {
		return level instanceof ServerLevel server && ItemStorage.SIDED.find(server, worldPosition.relative(d), d.getOpposite()) != null;
	}

	/** Takes a loose item entity lying on the belt; returns how many were taken. */
	public int absorb(ItemStack stack) {
		double p = entryProgress(null);
		if (kind() != ConveyorBlock.Kind.BELT || !room(p)) return 0;
		place(new Entry(stack.copy(), p, -1));
		changed();
		return stack.getCount();
	}

	/** Fabric storage for one side: insertion only, one stack at a time. */
	public @Nullable Storage<ItemVariant> storage(@Nullable Direction side) {
		double p = entryProgress(side);
		if (Double.isNaN(p)) return null;
		return (InsertionOnlyStorage<ItemVariant>) (resource, maxAmount, tx) -> {
			if (resource.isBlank() || maxAmount <= 0 || !room(p)) return 0;
			participant.updateSnapshots(tx);
			ItemStack stack = resource.toStack((int) Math.min(maxAmount, resource.getItem().getDefaultMaxStackSize()));
			place(new Entry(stack, p, chooseExit(stack)));
			return stack.getCount();
		};
	}

	private final class Participant extends SnapshotParticipant<List<Entry>> {
		@Override
		protected List<Entry> createSnapshot() {
			List<Entry> copy = new ArrayList<>(items.size());
			for (Entry e : items) copy.add(e.copy());
			return copy;
		}

		@Override
		protected void readSnapshot(List<Entry> snapshot) {
			items = snapshot;
		}

		@Override
		protected void onFinalCommit() {
			changed();
		}
	}

	/** Everything still on the belt (broken block). */
	public List<ItemStack> drops() {
		List<ItemStack> out = new ArrayList<>();
		for (Entry e : items) out.add(e.stack);
		if (!filter.isEmpty()) out.add(filter);
		return out;
	}

	// --- Rendering helper -------------------------------------------------------------------------------------

	/** Where an item at progress {@code p} sits, block-local (0..1), for the renderer. */
	public static Vec3 localPosition(BlockState state, double p, int exit) {
		ConveyorBlock.Kind kind = state.getBlock() instanceof ConveyorBlock b ? b.kind : ConveyorBlock.Kind.BELT;
		Direction f = state.getValue(ConveyorBlock.FACING);
		double h = 3.0 / 16;
		return switch (kind) {
			case LIFT -> new Vec3(0.5, 0.1 + 0.85 * p, 0.5);
			case INCLINE -> along(f, p, h + p * 1.0 - 0.1);
			case DECLINE -> along(f, p, h + (1 - p) * 1.0 - 0.1);
			default -> {
				if (exit < 0 || p <= 0.5) yield along(f, p, h);
				Direction d = Direction.from3DDataValue(exit);
				double t = (p - 0.5) * 2 * 0.5; // centre -> side edge
				yield new Vec3(0.5 + d.getStepX() * t, h, 0.5 + d.getStepZ() * t);
			}
		};
	}

	private static Vec3 along(Direction f, double p, double y) {
		double t = p - 0.5;
		return new Vec3(0.5 + f.getStepX() * t, y, 0.5 + f.getStepZ() * t);
	}

	// --- Sync and saving ------------------------------------------------------------------------------------

	private void changed() {
		setChanged();
		if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("items", Entry.CODEC.listOf(), items);
		if (!filter.isEmpty()) output.store("filter", ItemStack.CODEC, filter);
		output.putInt("next_exit", nextExit);
		output.putBoolean("left", left);
		output.putDouble("speed", speed);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		List<Entry> loaded = new ArrayList<>(input.read("items", Entry.CODEC.listOf()).orElse(List.of()));
		// Keep the previous positions of items we already drew, so an update doesn't make them jump.
		for (Entry e : loaded) {
			for (Entry old : items) {
				if (ItemStack.isSameItemSameComponents(old.stack, e.stack) && Math.abs(old.progress - e.progress) < 0.3) {
					e.previous = old.previous;
					e.progress = Math.max(e.progress, Math.min(old.progress, 1.0));
					break;
				}
			}
		}
		loaded.sort((a, b) -> Double.compare(b.progress, a.progress));
		items = loaded;
		filter = input.read("filter", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		nextExit = input.getIntOr("next_exit", 0);
		left = input.getBooleanOr("left", false);
		speed = input.getDoubleOr("speed", speed);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
