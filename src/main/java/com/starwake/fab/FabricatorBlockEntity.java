package com.starwake.fab;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.registry.ModFabrication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Fabricator (M8e-1): builds components and parts from {@link Blueprint}s. The player picks a
 * blueprint on the screen (&lt; &gt;); the machine looks through its nine input slots for items that
 * are the right kind and pass every gate, and when the whole list is there (and the output slot has
 * room) it takes them and runs for the blueprint's energy. The screen shows each input green, missing,
 * or present but failing a named gate (the same {@link Blueprint#match} runs on the client).
 */
public class FabricatorBlockEntity extends MachineBlockEntity {
	public static final int INPUTS = 9, OUTPUT = 9;
	public static final MachineMenu.Layout LAYOUT;

	static {
		List<int[]> slots = new ArrayList<>();
		for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) slots.add(new int[] {24 + c * 18, 14 + r * 18});
		slots.add(new int[] {150, 34});
		LAYOUT = new MachineMenu.Layout(slots, new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8}, new int[] {OUTPUT}, new int[] {}, new int[] {122, 39},
				MachineMenu.Layout.NO_TANKS);
	}

	public static final int SELECTED = MachineMenu.TANKS, BLUEPRINTS = MachineMenu.TANKS + 1;
	public static final int IDLE = 0, WORKING = 1, BLOCKED = 2;

	private @Nullable Identifier selected;
	private int selectedIndex;
	private ItemStack batch = ItemStack.EMPTY;
	private long energyNeeded, energyDone;
	private int status;

	public FabricatorBlockEntity(BlockPos pos, BlockState state) {
		super(ModFabrication.FABRICATOR_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModFabrication.FABRICATOR_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot < INPUTS;
	}

	private List<Map.Entry<Identifier, Blueprint>> blueprints() {
		if (level == null) return List.of();
		return level.registryAccess().lookup(Blueprint.REGISTRY_KEY).map(Blueprint::sorted).orElse(List.of());
	}

	private Map.@Nullable Entry<Identifier, Blueprint> current(List<Map.Entry<Identifier, Blueprint>> all) {
		if (all.isEmpty()) return null;
		if (selected != null) {
			for (int i = 0; i < all.size(); i++) {
				if (all.get(i).getKey().equals(selected)) {
					selectedIndex = i;
					return all.get(i);
				}
			}
		}
		selectedIndex = Math.floorMod(selectedIndex, all.size());
		selected = all.get(selectedIndex).getKey();
		return all.get(selectedIndex);
	}

	@Override
	public boolean onButton(int id, Player player) {
		var all = blueprints();
		if (all.isEmpty() || id > 1) return false;
		current(all);
		selectedIndex = Math.floorMod(selectedIndex + (id == 0 ? -1 : 1), all.size());
		selected = all.get(selectedIndex).getKey();
		setChanged();
		return true;
	}

	/** Selects a blueprint by id (commands, tests). */
	public boolean select(Identifier id) {
		for (var e : blueprints()) {
			if (e.getKey().equals(id)) {
				selected = id;
				setChanged();
				return true;
			}
		}
		return false;
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		if (batch.isEmpty() && (level.getGameTime() + worldPosition.asLong()) % 10 == 0) {
			status = IDLE;
			var entry = current(blueprints());
			if (entry != null) {
				Blueprint bp = entry.getValue();
				List<ItemStack> inputs = items.subList(0, INPUTS);
				Blueprint.Match match = bp.match(inputs);
				if (match.complete()) {
					ItemStack product = bp.product(inputs, match);
					ItemStack out = items.get(OUTPUT);
					if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, product) || out.getCount() + product.getCount() > out.getMaxStackSize())) {
						status = BLOCKED;
					} else {
						for (int[] used : match.slots()) for (int i = 0; i < used.length; i++) if (used[i] > 0) items.get(i).shrink(used[i]);
						batch = product;
						energyNeeded = Math.max(Math.round(bp.energy() * 1000), EnergyNode.perTick(spec().power()) * 20);
						energyDone = 0;
						setChanged();
					}
				}
			}
		}
		if (!batch.isEmpty()) {
			status = WORKING;
			if (energyDone < energyNeeded && drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
			}
			if (energyDone >= energyNeeded) {
				ItemStack out = items.get(OUTPUT);
				if (out.isEmpty()) items.set(OUTPUT, batch);
				else if (ItemStack.isSameItemSameComponents(out, batch) && out.getCount() + batch.getCount() <= out.getMaxStackSize()) out.grow(batch.getCount());
				else status = BLOCKED;
				if (status != BLOCKED) {
					batch = ItemStack.EMPTY;
					energyNeeded = energyDone = 0;
					status = IDLE;
				}
				setChanged();
			}
		}
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
		}
	}

	@Override
	protected void writeData(int[] v) {
		v[MachineMenu.STATUS] = status;
		var all = blueprints();
		current(all);
		v[SELECTED] = selectedIndex;
		v[BLUEPRINTS] = all.size();
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		selected = input.getString("blueprint").map(Identifier::tryParse).orElse(null);
		batch = input.read("batch", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (selected != null) output.putString("blueprint", selected.toString());
		if (!batch.isEmpty()) output.store("batch", ItemStack.CODEC, batch);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
	}
}
