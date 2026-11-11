package com.starwake.ship.edit;

import com.starwake.ship.ShipEntity;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Vanilla block placement, done in a ship's own frame (Milestone 4). Blocks orient themselves
 * from the context: which face you clicked, where on it, and which way you look. Here all of
 * those are ship-local, so stairs, thrusters, logs and doors face the right way relative to
 * the ship at any attitude.
 *
 * Positions are ship-local shifted by {@code offset} (a world cell near the ship), so the odd
 * block that peeks at its neighbours in the level reads loaded chunks around the ship rather
 * than wherever the local coordinates would land. (Neighbour-shaped blocks such as fences and
 * panes therefore don't connect to ship blocks yet: a known Milestone 4 limit.)
 */
public class ShipPlaceContext extends BlockPlaceContext {
	private final Vec3 localLook;

	private ShipPlaceContext(Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit, Vec3 localLook) {
		super(player.level(), player, hand, stack, hit);
		this.localLook = localLook;
	}

	/**
	 * @param local    the ship block that was clicked
	 * @param face     the clicked face (ship-local)
	 * @param hitLocal where on it (ship-local coordinates)
	 * @param offset   world cell the local frame is shifted to (see class comment)
	 */
	public static ShipPlaceContext create(ShipEntity ship, Player player, InteractionHand hand, BlockPos local, Direction face,
			Vec3 hitLocal, BlockPos offset) {
		Vec3 view = player.getViewVector(1.0f);
		Vector3f l = ship.orientation().transformInverse(new Vector3f((float) view.x, (float) view.y, (float) view.z));
		BlockHitResult hit = new BlockHitResult(hitLocal.add(offset.getX(), offset.getY(), offset.getZ()), face,
				local.offset(offset), false);
		return new ShipPlaceContext(player, hand, player.getItemInHand(hand), hit, new Vec3(l.x, l.y, l.z));
	}

	@Override
	public Direction getHorizontalDirection() {
		return Direction.getApproximateNearest(localLook.x, 0, localLook.z);
	}

	@Override
	public float getRotation() {
		return (float) Math.toDegrees(Math.atan2(-localLook.x, localLook.z));
	}

	@Override
	public Direction getNearestLookingDirection() {
		return getNearestLookingDirections()[0];
	}

	@Override
	public Direction getNearestLookingVerticalDirection() {
		return localLook.y > 0 ? Direction.UP : Direction.DOWN;
	}

	@Override
	public Direction[] getNearestLookingDirections() {
		Direction[] all = Direction.values().clone();
		Arrays.sort(all, Comparator.comparingDouble(d -> -(d.getStepX() * localLook.x + d.getStepY() * localLook.y + d.getStepZ() * localLook.z)));
		return all;
	}
}
