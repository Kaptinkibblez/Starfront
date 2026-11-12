package com.starwake.process;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.logistics.FluidTankBlockEntity;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ionising radiation (M8c-3a). Every species' activity and gamma dose-rate constant come from its
 * elements (species.json: {@code activity} Bq/kg, {@code gamma} Sv m^2 / (Bq h)), so uraninite, monazite
 * residues, radium-bearing slimes and radon gas are radioactive in proportion to what's really in them.
 *
 * <p>Once a second each player takes a dose from what they carry (~0.4 m away), from containers,
 * machines, tanks and dropped items within {@link #RANGE} blocks (inverse square, no shielding until
 * the suit upgrades of 8h), and from breathing radon in a gas cloud. The <i>acute</i> dose (Sv) heals
 * with a half-life of one game day and follows real acute radiation syndrome thresholds: 0.5 Sv
 * nausea, 1 Sv weakness, 2 Sv radiation sickness, 4 Sv severe, 8 Sv fatal within minutes. Dose and
 * dose rate are a synced player attachment for the HUD.
 */
public final class Radiation {
	/** Acute dose (Sv) and current dose rate (Sv/h). */
	public record State(double dose, double rate) {
		public static final State NONE = new State(0, 0);
		public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.DOUBLE.fieldOf("dose").forGetter(State::dose),
				Codec.DOUBLE.optionalFieldOf("rate", 0.0).forGetter(State::rate)
		).apply(i, State::new));
		public static final StreamCodec<io.netty.buffer.ByteBuf, State> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.DOUBLE, State::dose,
				ByteBufCodecs.DOUBLE, State::rate, State::new);
	}

	public static final AttachmentType<State> STATE = AttachmentRegistry.create(StarwakeMod.id("radiation"),
			b -> b.persistent(State.CODEC).syncWith(State.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));
	public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, StarwakeMod.id("radiation"));
	public static final int RANGE = 8;
	/** Distance of carried items from the body (m). */
	private static final double CARRIED = 0.4;
	/** Radon progeny inhalation: Sv/h per Bq/m^3 (ICRP, equilibrium factor 0.4). */
	private static final double RADON_SV_H_PER_BQ_M3 = 9e-9;
	/** Healing half-life of the acute dose, seconds (one game day). */
	private static final double HEAL_HALF_LIFE = 1200;

	private static final Map<String, double[]> SPECIES = new HashMap<>();
	private static final Map<Object, double[]> STACKS = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Object, double[]> eldest) {
			return size() > 4096;
		}
	};

	private Radiation() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if ((server.getTickCount() + player.getId()) % 20 == 0) update(player);
			}
		});
	}

	/** {activity Bq/kg, gamma (Sv m^2/h)/kg} of one species. */
	public static double[] perKg(String species) {
		synchronized (SPECIES) {
			return SPECIES.computeIfAbsent(species, id -> {
				Species.Info info = Species.get(id).orElse(null);
				if (info == null) return new double[2];
				double a = 0, g = 0;
				for (var e : info.elements().entrySet()) {
					Species.Info el = Species.get(e.getKey()).orElse(null);
					if (el == null || el.activity() <= 0) continue;
					a += e.getValue() * el.activity();
					g += e.getValue() * el.activity() * el.gamma();
				}
				return new double[] {a, g};
			});
		}
	}

	/** {activity Bq, gamma Sv m^2/h} of a mass of species (kg). */
	public static double[] of(Map<String, Double> species) {
		double a = 0, g = 0;
		for (var e : species.entrySet()) {
			double[] k = perKg(e.getKey());
			a += k[0] * e.getValue();
			g += k[1] * e.getValue();
		}
		return new double[] {a, g};
	}

	/** {activity Bq, gamma} of one item of this stack (cached per item + components). */
	public static double[] perItem(ItemStack stack) {
		if (stack.isEmpty()) return new double[2];
		Object key = new Key(stack.getItem(), stack.getComponentsPatch());
		synchronized (STACKS) {
			double[] cached = STACKS.get(key);
			if (cached != null) return cached;
		}
		double[] v = ProcessMachineBlockEntity.material(stack).map(Radiation::of).orElseGet(() -> elementItem(stack));
		synchronized (STACKS) {
			STACKS.put(key, v);
		}
		return v;
	}

	private record Key(Item item, DataComponentPatch patch) {}

	/** Element items without a composition: gas canisters and flasks hold 1 kg, samples 1 g. */
	private static double[] elementItem(ItemStack stack) {
		return com.starwake.chem.Chemistry.elementOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
				.map(e -> {
					double[] k = perKg(e.symbol());
					double kg = e.form().equals("sample") ? 0.001 : 1.0;
					return new double[] {k[0] * kg, k[1] * kg};
				}).orElse(new double[2]);
	}

	/** Gamma (Sv m^2/h) of everything in a container or machine. */
	public static double gamma(BlockEntity be) {
		double g = 0;
		if (be instanceof Container c) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty()) g += perItem(s)[1] * s.getCount();
			}
		}
		if (be instanceof ProcessMachineBlockEntity m) g += of(m.contents())[1];
		if (be instanceof FluidTankBlockEntity t) {
			Map<String, Double> kg = new HashMap<>();
			t.chemicals().contents().forEach((s, d) -> kg.put(s, d / (double) Chemicals.KG));
			g += of(kg)[1];
		}
		return g;
	}

	/** Current dose rate (Sv/h) for a player. */
	public static double rate(ServerPlayer player) {
		ServerLevel level = player.level();
		double rate = 0;
		var inv = player.getInventory();
		double carried = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) carried += perItem(s)[1] * s.getCount();
		}
		rate += carried / (CARRIED * CARRIED);
		Vec3 eye = player.getEyePosition();
		int cx0 = (player.getBlockX() - RANGE) >> 4, cx1 = (player.getBlockX() + RANGE) >> 4;
		int cz0 = (player.getBlockZ() - RANGE) >> 4, cz1 = (player.getBlockZ() + RANGE) >> 4;
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				var chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					double d2 = Vec3.atCenterOf(be.getBlockPos()).distanceToSqr(eye);
					if (d2 > RANGE * RANGE) continue;
					double g = gamma(be);
					if (g > 0) rate += g / Math.max(0.25, d2);
				}
			}
		}
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(eye, eye).inflate(RANGE))) {
			double g = perItem(item.getItem())[1] * item.getItem().getCount();
			if (g > 0) rate += g / Math.max(0.25, item.position().distanceToSqr(eye));
		}
		rate += GasHazards.inhaledActivity(player) * RADON_SV_H_PER_BQ_M3;
		return rate;
	}

	private static void update(ServerPlayer player) {
		State old = player.getAttachedOrElse(STATE, State.NONE);
		double rate = rate(player);
		double dose = old.dose() * Math.pow(0.5, 1 / HEAL_HALF_LIFE) + rate / 3600;
		if (dose < 1e-7) dose = 0;
		if (rate < 1e-10) rate = 0;
		if (dose != old.dose() || rate != old.rate()) player.setAttached(STATE, dose == 0 && rate == 0 ? null : new State(dose, rate));
		effects(player, dose);
	}

	private static void effects(ServerPlayer player, double dose) {
		if (dose < 0.5 || player.isCreative() || player.isSpectator()) return;
		ServerLevel level = player.level();
		long t = level.getGameTime() / 20;
		if (t % 20 == 0) player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, dose >= 2 ? 1 : 0));
		if (dose >= 1) player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, dose >= 4 ? 1 : 0, false, false));
		if (dose >= 4 && t % 15 == 0) player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 160, 0));
		float damage = dose >= 8 ? 2 : dose >= 4 && t % 3 == 0 ? 1 : dose >= 2 && t % 10 == 0 ? 1 : 0;
		if (damage > 0) player.hurtServer(level, level.damageSources().source(DAMAGE), damage);
	}
}
