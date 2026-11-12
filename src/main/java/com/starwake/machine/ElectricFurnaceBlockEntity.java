package com.starwake.machine;

import com.starwake.registry.ModMachines;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Electric Furnace (M8b): runs vanilla smelting recipes on grid power instead of fuel,
 * {@code speed} x faster, drawing {@code power} kW while it works. The first consumer of the grid;
 * the metallurgy furnaces of 8c-8d build on it.
 */
public class ElectricFurnaceBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(List.of(new int[] {56, 35}, new int[] {116, 35}),
			new int[] {0}, new int[] {1}, new int[] {}, new int[] {80, 39}, MachineMenu.Layout.NO_TANKS);
	private final RecipeManager.CachedCheck<SingleRecipeInput, SmeltingRecipe> check = RecipeManager.createCheck(RecipeType.SMELTING);

	public ElectricFurnaceBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.ELECTRIC_FURNACE_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected net.minecraft.world.inventory.MenuType<MachineMenu> menuType() {
		return ModMachines.ELECTRIC_FURNACE_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		ItemStack input = items.get(0);
		boolean working = false;
		if (!input.isEmpty()) {
			SingleRecipeInput recipeInput = new SingleRecipeInput(input);
			var recipe = check.getRecipeFor(recipeInput, level).orElse(null);
			if (recipe != null) {
				ItemStack result = recipe.value().assemble(recipeInput);
				ItemStack out = items.get(1);
				boolean fits = out.isEmpty() || ItemStack.isSameItemSameComponents(out, result)
						&& out.getCount() + result.getCount() <= Math.min(out.getMaxStackSize(), getMaxStackSize());
				progressMax = Math.max(1, (int) Math.ceil(recipe.value().cookingTime() / spec().speed()));
				if (fits && drawPower(spec().power())) {
					working = true;
					if (++progress >= progressMax) {
						progress = 0;
						if (out.isEmpty()) items.set(1, result.copy());
						else out.grow(result.getCount());
						input.shrink(1);
						setChanged();
					}
				}
			} else {
				progress = 0;
			}
		} else {
			progress = 0;
		}
		if (getBlockState().getValue(MachineBlock.LIT) != working) level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
	}
}
