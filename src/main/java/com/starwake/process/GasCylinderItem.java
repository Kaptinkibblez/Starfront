package com.starwake.process;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.planet.SurveySuitItem;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantItemStorage;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Gas Cylinder (M8c-3a): a portable pressure cylinder holding up to {@link #CAPACITY_KG} kg of one
 * gas (oxygen, argon, hydrogen, chlorine...). It is a Fabric fluid-storage item, so it fills from and
 * empties into tanks, machine ports and anything else with fluid storage by using it on them. Using an
 * oxygen cylinder while wearing the Survey Suit tops up the suit's oxygen at a human's real
 * consumption (about 0.84 kg of O2 a day, so a full suit is ~3 g).
 */
public class GasCylinderItem extends Item {
	public static final double CAPACITY_KG = 5;
	/** kg of O2 a person breathes per tick (0.84 kg/day). */
	public static final double O2_PER_TICK = 0.84 / 86400 / 20;

	/** What a cylinder holds: one species and droplets of it. */
	public record Fill(String species, long amount) {
		public static final Codec<Fill> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("species").forGetter(Fill::species),
				Codec.LONG.fieldOf("amount").forGetter(Fill::amount)
		).apply(i, Fill::new));
		public static final StreamCodec<ByteBuf, Fill> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Fill::species,
				ByteBufCodecs.VAR_LONG, Fill::amount, Fill::new);

		public double kg() {
			return amount / (double) Chemicals.KG;
		}
	}

	public GasCylinderItem(Properties properties) {
		super(properties);
	}

	public static DataComponentType<Fill> fillType() {
		return com.starwake.registry.ModComponents.GAS;
	}

	public static Fill fill(ItemStack stack) {
		return stack.get(fillType());
	}

	/** Gases only: species that are gases at room temperature. */
	public static boolean holds(String species) {
		return Species.get(species).map(s -> s.kind() == Species.Kind.GAS || s.gasAtRoom()).orElse(false);
	}

	@Override
	public Component getName(ItemStack stack) {
		Fill fill = fill(stack);
		if (fill == null || fill.amount() <= 0) return Component.translatable("item.starwake.gas_cylinder.empty");
		Component gas = Species.get(fill.species()).map(Species.Info::displayName).orElse(Component.literal(fill.species()));
		return Component.translatable("item.starwake.gas_cylinder.of", gas);
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		Fill fill = fill(stack);
		return fill != null && fill.amount() > 0;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		Fill fill = fill(stack);
		return fill == null ? 0 : (int) Math.round(13 * Math.min(1, fill.kg() / CAPACITY_KG));
	}

	@Override
	public int getBarColor(ItemStack stack) {
		Fill fill = fill(stack);
		return fill == null ? 0xFFFFFF : Species.get(fill.species()).map(Species.Info::color).orElse(0xFFFFFF);
	}

	/** Use in the air: an oxygen cylinder refills the worn Survey Suit. */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		Fill fill = fill(stack);
		ItemStack suit = player.getItemBySlot(EquipmentSlot.CHEST);
		if (fill == null || !fill.species().equals("O2") || !(suit.getItem() instanceof SurveySuitItem)) return InteractionResult.PASS;
		int missing = SurveySuitItem.maxOxygen(suit) - SurveySuitItem.oxygen(suit);
		if (missing <= 0) return InteractionResult.PASS;
		if (player instanceof ServerPlayer) {
			long need = (long) Math.ceil(missing * O2_PER_TICK * Chemicals.KG);
			long take = Math.min(need, fill.amount());
			int ticks = take >= need ? missing : (int) Math.floor(take / (O2_PER_TICK * Chemicals.KG));
			SurveySuitItem.setOxygen(suit, SurveySuitItem.oxygen(suit) + ticks);
			stack.set(fillType(), take >= fill.amount() ? null : new Fill("O2", fill.amount() - take));
			level.playSound(null, player.blockPosition(), SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.4f, 1.6f);
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * Use on a tank or machine: fill the cylinder from it (only ever this direction, so a held click
	 * can't toggle the gas back and forth). Prefers what the cylinder already holds.
	 */
	public static boolean fillFrom(net.fabricmc.fabric.api.transfer.v1.storage.Storage<FluidVariant> world, Player player, InteractionHand hand) {
		var cylinder = ContainerItemContext.forPlayerInteraction(player, hand).find(net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.ITEM);
		if (cylinder == null) return false;
		long moved;
		try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
			moved = net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil.move(world, cylinder, v -> true, Long.MAX_VALUE, tx);
			if (moved > 0) tx.commit();
		}
		if (moved > 0) player.level().playSound(null, player.blockPosition(), SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.5f, 1.4f);
		return moved > 0;
	}

	/** Sneak-use on anything with fluid storage: empty the cylinder into it. */
	@Override
	public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null || !player.isSecondaryUseActive()) return InteractionResult.PASS;
		var world = net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(context.getLevel(), context.getClickedPos(), context.getClickedFace());
		if (world == null) return InteractionResult.PASS;
		if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
		var cylinder = ContainerItemContext.forPlayerInteraction(player, context.getHand()).find(net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.ITEM);
		if (cylinder == null) return InteractionResult.PASS;
		long moved;
		try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
			moved = net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil.move(cylinder, world, v -> true, Long.MAX_VALUE, tx);
			if (moved > 0) tx.commit();
		}
		if (moved > 0) context.getLevel().playSound(null, context.getClickedPos(), SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.5f, 1.4f);
		return moved > 0 ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	/** Fabric fluid storage for a cylinder in any container. */
	public static class Storage extends SingleVariantItemStorage<FluidVariant> {
		public Storage(ContainerItemContext context) {
			super(context);
		}

		@Override
		protected FluidVariant getBlankResource() {
			return FluidVariant.blank();
		}

		@Override
		protected FluidVariant getResource(ItemVariant item) {
			Fill fill = item.getComponents().get(fillType());
			return fill == null || fill.amount() <= 0 ? FluidVariant.blank() : Chemicals.variant(fill.species());
		}

		@Override
		protected long getAmount(ItemVariant item) {
			Fill fill = item.getComponents().get(fillType());
			return fill == null ? 0 : fill.amount();
		}

		@Override
		protected long getCapacity(FluidVariant variant) {
			return Math.round(CAPACITY_KG * Chemicals.KG);
		}

		@Override
		protected boolean canInsert(FluidVariant variant) {
			return Chemicals.speciesOf(variant).map(GasCylinderItem::holds).orElse(false);
		}

		@Override
		protected ItemVariant getUpdatedVariant(ItemVariant current, FluidVariant resource, long amount) {
			DataComponentPatch patch = amount <= 0 || resource.isBlank()
					? DataComponentPatch.builder().remove(fillType()).build()
					: DataComponentPatch.builder().set(fillType(), new Fill(Chemicals.speciesOf(resource).orElseThrow(), amount)).build();
			return current.withComponents(patch);
		}
	}
}
