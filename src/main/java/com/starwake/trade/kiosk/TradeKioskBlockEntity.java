package com.starwake.trade.kiosk;

import com.starwake.registry.ModHubBlocks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A Trade Kiosk's stock (M9 player shops): nine slots the owner fills (sneak-use opens a one-row chest), a price per
 * slot (credits per item; 0 = not for sale), and the owner. Hoppers may stock it from any side but never take from it.
 */
public class TradeKioskBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	public static final int SLOTS = 9;
	private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
	private final long[] prices = new long[SLOTS];
	private @Nullable UUID owner;
	private String ownerName = "";

	public TradeKioskBlockEntity(BlockPos pos, BlockState state) {
		super(ModHubBlocks.TRADE_KIOSK_BE, pos, state);
	}

	public @Nullable UUID owner() {
		return owner;
	}

	public String ownerName() {
		return ownerName;
	}

	public void setOwner(UUID owner, String name) {
		this.owner = owner;
		this.ownerName = name;
		setChanged();
	}

	public long price(int slot) {
		return slot >= 0 && slot < SLOTS ? prices[slot] : 0;
	}

	public void setPrice(int slot, long price) {
		if (slot < 0 || slot >= SLOTS) return;
		prices[slot] = Math.max(0, Math.min(price, 1_000_000_000L));
		setChanged();
	}

	@Override
	protected Component getDefaultName() {
		return Component.translatable("block.starwake.trade_kiosk");
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
		return new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1, id, inventory, this, 1);
	}

	@Override
	public int getContainerSize() {
		return SLOTS;
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		return new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8};
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
		return true;
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return false;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
		var p = input.read("prices", com.mojang.serialization.Codec.LONG.listOf()).orElse(java.util.List.of());
		for (int i = 0; i < SLOTS && i < p.size(); i++) prices[i] = p.get(i);
		Optional<UUID> o = input.read("owner", UUIDUtil.CODEC);
		owner = o.orElse(null);
		ownerName = input.getStringOr("owner_name", "");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, items);
		output.store("prices", com.mojang.serialization.Codec.LONG.listOf(), java.util.Arrays.stream(prices).boxed().toList());
		if (owner != null) output.store("owner", UUIDUtil.CODEC, owner);
		output.putString("owner_name", ownerName);
	}
}
