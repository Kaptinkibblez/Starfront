package com.starwake.planet;

import com.starwake.StarwakeMod;
import com.starwake.registry.ModItems;
import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Planet hazards (M5): once a second, every player on a hostile world is checked against the
 * planet's {@link Hazards}. Shelter comes from a sealed ship cabin (pressurized: breathable air,
 * room temperature) or a {@link SurveySuitItem} (own oxygen, toxic seal, thermal rating).
 *
 * <ul>
 *   <li>No breathable air: suit oxygen drains; without it, hypoxia damage.</li>
 *   <li>Toxic air: the same, plus burns that scale with toxicity (the suit seals it out while
 *       it has oxygen).</li>
 *   <li>Cold below {@link Hazards#COLD_LIMIT}: vanilla freezing (frost overlay and damage),
 *       harder below -100 C.</li>
 *   <li>Heat above {@link Hazards#HEAT_LIMIT}: heat damage scaling with temperature.</li>
 * </ul>
 * Oxygen refills in breathable air and in sealed cabins. Creative and spectator players are
 * exempt. O(players) once per second.
 */
public final class PlanetHazards {
	public static final ResourceKey<DamageType> HYPOXIA = key("hypoxia");
	public static final ResourceKey<DamageType> TOXIC_ATMOSPHERE = key("toxic_atmosphere");
	public static final ResourceKey<DamageType> EXTREME_HEAT = key("extreme_heat");
	private static final int INTERVAL = 20;
	private static final int REFILL_PER_SECOND = 600; // 30 s of air per second of breathing

	/** What protects a player right now (also used by the client HUD). */
	public enum Shelter { NONE, SUIT, CABIN }

	private PlanetHazards() {}

	private static ResourceKey<DamageType> key(String name) {
		return ResourceKey.create(Registries.DAMAGE_TYPE, StarwakeMod.id(name));
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PlanetHazards::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isCreative() || player.isSpectator() || !player.isAlive()) continue;
			apply(player, Planets.hazardsOf(player.level().dimension()));
		}
	}

	/** The pressurized ship cabin the player's head is in, if any. */
	public static boolean inPressurizedCabin(net.minecraft.world.entity.player.Player player) {
		// Trade hub interiors and station hangars are pressurized too (M7).
		if (com.starwake.trade.HubZones.isPressurized(player.level(), player.getEyePosition())) return true;
		for (ShipEntity ship : ShipCollisions.shipsIn(player.level())) {
			if (ship.getBoundingBox().inflate(2).contains(player.getEyePosition()) && ship.isInPressurizedCabin(player.getEyePosition())) {
				return true;
			}
		}
		return false;
	}

	public static ItemStack suit(net.minecraft.world.entity.player.Player player) {
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		return chest.is(ModItems.SURVEY_SUIT) ? chest : ItemStack.EMPTY;
	}

	private static void apply(ServerPlayer player, Hazards hazards) {
		ServerLevel level = player.level();
		ItemStack suit = suit(player);
		boolean cabin = inPressurizedCabin(player);
		boolean airOk = hazards.breathable() && !hazards.toxic();

		// Breathing.
		if (airOk || cabin) {
			if (!suit.isEmpty()) SurveySuitItem.setOxygen(suit, SurveySuitItem.oxygen(suit) + REFILL_PER_SECOND);
		} else if (!suit.isEmpty() && SurveySuitItem.oxygen(suit) > 0) {
			SurveySuitItem.setOxygen(suit, SurveySuitItem.oxygen(suit) - INTERVAL);
		} else {
			if (!hazards.breathable()) player.hurtServer(level, level.damageSources().source(HYPOXIA), 2.0f);
			if (hazards.toxic()) player.hurtServer(level, level.damageSources().source(TOXIC_ATMOSPHERE), 1.0f + 3.0f * hazards.toxicity());
		}

		// Temperature.
		if (cabin) return;
		float t = hazards.temperature();
		boolean suited = !suit.isEmpty() && SurveySuitItem.protectsFrom(t);
		if (suited) return;
		if (hazards.cold()) {
			// Keep the player at the vanilla freeze threshold: frost overlay, slowness, freeze damage.
			player.setTicksFrozen(Math.max(player.getTicksFrozen(), player.getTicksRequiredToFreeze() + 42));
			if (t < -100) player.hurtServer(level, level.damageSources().source(DamageTypes.FREEZE), 1.0f + (-100 - t) / 80f);
		} else if (hazards.hot()) {
			player.hurtServer(level, level.damageSources().source(EXTREME_HEAT), 1.0f + (t - Hazards.HEAT_LIMIT) / 60f);
		}
	}

	/** Client/server: what currently shelters a player from these hazards. */
	public static Shelter shelter(net.minecraft.world.entity.player.Player player, Hazards hazards) {
		if (inPressurizedCabin(player)) return Shelter.CABIN;
		ItemStack suit = suit(player);
		if (!suit.isEmpty() && (SurveySuitItem.oxygen(suit) > 0 || (hazards.breathable() && !hazards.toxic()))
				&& SurveySuitItem.protectsFrom(hazards.temperature())) {
			return Shelter.SUIT;
		}
		return Shelter.NONE;
	}
}
