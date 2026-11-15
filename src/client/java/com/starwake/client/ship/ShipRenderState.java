package com.starwake.client.ship;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame render state for a ship: the block states to draw (cached per ship). */
public final class ShipRenderState extends EntityRenderState {
	/** Visible mesh sections and their light arrays (parallel lists). */
	final java.util.List<ShipMesh> meshes = new java.util.ArrayList<>();
	final java.util.List<int[]> lights = new java.util.ArrayList<>();
	final Quaternionf orientation = new Quaternionf();
	Vec3 pivot = Vec3.ZERO;
	/** Block entities aboard (M9 pass 2): local position and the renderer's own state, per frame. */
	final java.util.List<net.minecraft.core.BlockPos> blockEntityPos = new java.util.ArrayList<>();
	final java.util.List<net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> blockEntities = new java.util.ArrayList<>();
}
