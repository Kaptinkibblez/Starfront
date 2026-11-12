package com.starwake.nuclear;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Species;
import com.starwake.registry.ModNuclear;
import com.starwake.ship.fuel.Propellants;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Fusion Reactor (M8g): an aneutronic D-He3 plant. It burns deuterium and helium-3 from its fuel tank at the
 * reaction's atom ratio; 3.5e14 J per kg of fuel (18.3 MeV per reaction) at the plant efficiency, so its rated
 * output takes micrograms per second. A redstone signal shuts it down. The same fuel feeds ships' quantum and
 * jump drives (M8f).
 */
public class FusionReactorBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(List.of(), new int[] {}, new int[] {}, new int[] {}, new int[] {80, 33},
			new int[] {40, -1, -1});
	public static final int OFF = 0, RUNNING = 1, NO_FUEL = 2, SHUTDOWN = 3;

	private final ChemicalTank fuel = new ChemicalTank(4, true, false, s -> s.equals("D2") || s.equals("He3"), this::setChanged);
	private int status;

	public FusionReactorBlockEntity(BlockPos pos, BlockState state) {
		super(ModNuclear.FUSION_REACTOR_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModNuclear.FUSION_REACTOR_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.GENERATOR;
	}

	public Storage<FluidVariant> fluidStorage() {
		return fuel;
	}

	@Override
	protected void work(ServerLevel level) {
		Nuclear n = Nuclear.get();
		powerKw = 0;
		long perTick = EnergyNode.perTick(spec().power());
		// kg of D + He3 per tick at rated output.
		double kgPerTick = perTick / (n.fusionJPerKg() * n.fusionEfficiency());
		double d2 = kgPerTick / (1 + Propellants.HE3_PER_D2), he3 = d2 * Propellants.HE3_PER_D2;
		boolean signal = level.hasNeighborSignal(worldPosition);
		boolean fuelled = fuel.kg("D2") >= d2 && fuel.kg("He3") >= he3;
		status = signal ? SHUTDOWN : !fuelled ? NO_FUEL : RUNNING;
		boolean running = status == RUNNING && energy < energyCapacity();
		if (running) {
			fuel.take("D2", d2);
			fuel.take("He3", he3);
			energy = Math.min(energyCapacity(), energy + perTick);
			powerKw = spec().power();
		}
		if (getBlockState().getValue(MachineBlock.LIT) != running) level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, running));
	}

	@Override
	protected void writeData(int[] v) {
		v[MachineMenu.STATUS] = status;
		v[MachineMenu.TANKS] = (int) Math.min(32000, Math.round(fuel.totalKg() * 10000));
		v[MachineMenu.TANKS + 1] = (int) Math.max(1, fuel.capacityKg() * 100);
		String main = fuel.main();
		v[MachineMenu.TANKS + 2] = main == null ? -1 : Species.get(main).map(Species.Info::index).orElse(-1);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		fuel.read(input, "fuel");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		fuel.write(output, "fuel");
	}
}
