package com.starwake.trade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Hub people (M7): guards and crew in a faction's uniform. They stay near their post (a home
 * radius inside the hub) and never trade (terminals do that).
 * <ul>
 *   <li><b>Guards</b> carry a sword, fight monsters that come in, defend each other, and attack
 *       players the faction considers hostile.</li>
 *   <li><b>Crew</b> wander the hub and run from trouble (they still alert guards).</li>
 * </ul>
 * Hurting one costs reputation with its faction (25), killing one much more (150), and turns
 * the guards on you. Faction, role and look are synced for the renderer.
 */
public class HubNpcEntity extends PathfinderMob {
	private static final EntityDataAccessor<String> FACTION = SynchedEntityData.defineId(HubNpcEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(HubNpcEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(HubNpcEntity.class, EntityDataSerializers.INT);
	public static final int VARIANTS = 4;

	private String hub = "";

	public HubNpcEntity(EntityType<? extends HubNpcEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 30)
				.add(Attributes.MOVEMENT_SPEED, 0.28)
				.add(Attributes.ATTACK_DAMAGE, 5)
				.add(Attributes.FOLLOW_RANGE, 24);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FACTION, "");
		builder.define(ROLE, "crew");
		builder.define(VARIANT, 0);
	}

	/** Sets who this is (called when a hub spawns its people). */
	public void setup(String hub, String faction, String role, int variant, BlockPos post, int radius) {
		this.hub = hub;
		entityData.set(FACTION, faction);
		entityData.set(ROLE, role);
		entityData.set(VARIANT, Math.floorMod(variant, VARIANTS));
		setHomeTo(post, radius);
		if (isGuard()) setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		setDropChance(EquipmentSlot.MAINHAND, 0);
	}

	public String faction() { return entityData.get(FACTION); }
	public String role() { return entityData.get(ROLE); }

	/** "Guard" or "Crew" (in chat, death messages and name tags). */
	@Override
	protected net.minecraft.network.chat.Component getTypeName() {
		return net.minecraft.network.chat.Component.translatable("entity.starwake.hub_npc." + role());
	}
	public int variant() { return entityData.get(VARIANT); }
	public String hub() { return hub; }
	public boolean isGuard() { return role().equals("guard"); }

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new OpenDoorGoal(this, true));
		goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, true) {
			@Override
			public boolean canUse() {
				return isGuard() && super.canUse();
			}
		});
		goalSelector.addGoal(3, new MoveTowardsRestrictionGoal(this, 0.7));
		goalSelector.addGoal(5, new RandomStrollGoal(this, 0.55, 80));
		goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
		goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true, (t, l) -> isGuard()));
		targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, true,
				(t, l) -> isGuard() && t instanceof ServerPlayer p && Economy.hostile(Economy.rep(p, faction()))));
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		boolean hurt = super.hurtServer(level, source, damage);
		if (hurt && source.getEntity() instanceof ServerPlayer player && !player.isCreative()) {
			Economy.addRep(player, faction(), isDeadOrDying() ? -150 : -25);
		}
		return hurt;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("hub", hub);
		output.putString("faction", faction());
		output.putString("role", role());
		output.putInt("variant", variant());
		if (hasHome()) {
			output.store("post", BlockPos.CODEC, getHomePosition());
			output.putInt("post_radius", (int) getHomeRadius());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		hub = input.getStringOr("hub", "");
		entityData.set(FACTION, input.getStringOr("faction", ""));
		entityData.set(ROLE, input.getStringOr("role", "crew"));
		entityData.set(VARIANT, input.getIntOr("variant", 0));
		input.read("post", BlockPos.CODEC).ifPresent(post -> setHomeTo(post, input.getIntOr("post_radius", 8)));
	}
}
