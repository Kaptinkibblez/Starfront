package com.starwake.machine;

import com.starwake.galaxy.Galaxy;
import com.starwake.galaxy.SolarSystem;
import com.starwake.registry.ModMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Screenless grid blocks (M8b): the Solar Panel and the Battery. Use one to read it.
 *
 * <ul>
 *   <li><b>Solar Panel</b>: {@code power} kW peak x sunlight x the star's light here. Sunlight is
 *       the daylight detector's model (sky light and sun angle; full sun in space if the sky is
 *       open). Starlight is luminosity / distance^2, relative to the Overworld under Solace, so a
 *       dim red dwarf's planets give little and inner planets more. Recomputed every 2 s.</li>
 *   <li><b>Battery</b>: {@code capacity} kWh, charging and discharging at up to {@code io} kW.</li>
 * </ul>
 */
public class PowerBlockEntity extends BlockEntity implements EnergyNode {
	/** The Overworld's distance from Solace: the "1 AU" for solar output. */
	private static final double REFERENCE_DISTANCE = 1.8e6;

	private final boolean solar;
	private long energy;
	private double sun, starlight = 1, outputKw;

	public PowerBlockEntity(BlockPos pos, BlockState state) {
		super(state.is(ModMachines.SOLAR_PANEL) ? ModMachines.SOLAR_PANEL_BE : ModMachines.BATTERY_BE, pos, state);
		this.solar = state.is(ModMachines.SOLAR_PANEL);
	}

	public MachineSpec spec() {
		return MachineSpec.of(getBlockState().getBlock());
	}

	public boolean solar() {
		return solar;
	}

	public double sun() {
		return sun;
	}

	public double starlight() {
		return starlight;
	}

	public double outputKw() {
		return outputKw;
	}

	@Override
	public Role energyRole() {
		return solar ? Role.GENERATOR : Role.STORAGE;
	}

	@Override
	public long energyStored() {
		return energy;
	}

	@Override
	public void setEnergyStored(long joules) {
		energy = Math.clamp(joules, 0, energyCapacity());
		setChanged();
	}

	@Override
	public long energyCapacity() {
		return solar ? EnergyNode.perTick(spec().power()) * 4 : spec().capacityJ();
	}

	@Override
	public long maxEnergyInput() {
		return solar ? 0 : EnergyNode.perTick(spec().io());
	}

	@Override
	public long maxEnergyOutput() {
		return solar ? EnergyNode.perTick(spec().power()) : EnergyNode.perTick(spec().io());
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, PowerBlockEntity be) {
		if (!(level instanceof ServerLevel server)) return;
		PowerNetworks.networkOf(server, pos);
		if (!be.solar) return;
		if ((server.getGameTime() + pos.asLong()) % 40 == 0) be.measure(server);
		be.outputKw = be.spec().power() * be.sun * be.starlight;
		be.energy = Math.min(be.energyCapacity(), be.energy + EnergyNode.perTick(be.outputKw));
	}

	private void measure(ServerLevel level) {
		BlockPos above = worldPosition.above();
		if (!level.canSeeSky(above)) {
			sun = 0;
		} else if (SolarSystem.isSpace(level.dimension())) {
			sun = 1;
		} else {
			int sky = level.getEffectiveSkyBrightness(above);
			float angle = level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, worldPosition) * (float) (Math.PI / 180.0);
			float offset = angle < (float) Math.PI ? 0.0F : (float) (Math.PI * 2);
			angle += (offset - angle) * 0.2F;
			sun = Mth.clamp(sky / 15.0 * Mth.cos(angle), 0, 1);
		}
		starlight = starlight(level, Vec3.atCenterOf(worldPosition));
	}

	/** Irradiance relative to the Overworld under Solace (1.0), from the system's star. */
	public static double starlight(ServerLevel level, Vec3 pos) {
		var star = SolarSystem.star(level).orElse(null);
		if (star == null) return 1;
		Vec3 here = SolarSystem.isSpace(level.dimension()) ? pos
				: SolarSystem.forDimension(level.registryAccess(), level.dimension()).map(e -> e.body().position()).orElse(null);
		if (here == null) return 1;
		double d = Math.max(1, here.distanceTo(star.body().position()));
		String system = SolarSystem.systemOf(level);
		double luminosity = Galaxy.star(system).map(s -> s.luminosity() + s.companion().map(Galaxy.Companion::luminosity).orElse(0.0))
				.orElse(1.0);
		return Math.min(4, luminosity * (REFERENCE_DISTANCE / d) * (REFERENCE_DISTANCE / d));
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if (level != null) PowerNetworks.invalidate(level, worldPosition);
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		if (level != null) PowerNetworks.invalidate(level, worldPosition);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		energy = input.getLongOr("energy", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putLong("energy", energy);
	}
}
