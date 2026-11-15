package com.starwake.client.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared label layout for world markers (M9): every HUD element that labels something in the world (bodies, trade
 * hubs) places its label here, and a label that would overlap one already placed this frame steps away until it's
 * clear. A new layout starts each rendered frame.
 */
public final class HudLabels {
	private static final List<int[]> PLACED = new ArrayList<>();
	private static long frame = -1;

	private HudLabels() {}

	/** Starts a new frame's layout if the frame counter moved on. */
	private static void sync(long currentFrame) {
		if (currentFrame != frame) {
			PLACED.clear();
			frame = currentFrame;
		}
	}

	/**
	 * Reserves a w x h label box centred on x at y, stepping by {@code step} (down if positive) up to 6 times while it
	 * overlaps another label; returns the y used.
	 */
	public static int place(long currentFrame, int x, int y, int w, int h, int step) {
		sync(currentFrame);
		int left = x - w / 2 - 2, right = x + w / 2 + 2;
		for (int tries = 0; tries < 6 && overlaps(left, y - 1, right, y + h); tries++) y += step;
		PLACED.add(new int[] {left, y - 1, right, y + h});
		return y;
	}

	/** Like {@link #place}, but returns {@link Integer#MIN_VALUE} (and reserves nothing) when no free spot is found. */
	public static int tryPlace(long currentFrame, int x, int y, int w, int h, int step) {
		sync(currentFrame);
		int left = x - w / 2 - 2, right = x + w / 2 + 2;
		for (int tries = 0; tries < 6; tries++, y += step) {
			if (!overlaps(left, y - 1, right, y + h)) {
				PLACED.add(new int[] {left, y - 1, right, y + h});
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static boolean overlaps(int x0, int y0, int x1, int y1) {
		for (int[] r : PLACED) if (x0 < r[2] && x1 > r[0] && y0 < r[3] && y1 > r[1]) return true;
		return false;
	}

	private static long lastCall;
	private static long counter;

	/**
	 * A frame id: HUD elements of one frame run back to back (well under 4 ms), frames are further apart, so a gap
	 * since the last label starts a new layout.
	 */
	public static long frame() {
		long now = System.nanoTime();
		if (now - lastCall > 4_000_000L) counter++;
		lastCall = now;
		return counter;
	}
}
