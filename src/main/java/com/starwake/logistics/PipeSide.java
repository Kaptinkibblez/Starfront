package com.starwake.logistics;

import net.minecraft.util.StringRepresentable;

/** One side of a fluid pipe: not connected, connected, or pulling from the storage there. */
public enum PipeSide implements StringRepresentable {
	NONE("none"), CONNECTED("connected"), EXTRACT("extract");

	private final String name;

	PipeSide(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}
}
