package com.starwake.planet.proc;

import net.minecraft.util.RandomSource;

/**
 * Seeded planet names: two or three syllables from small consonant/vowel tables, so names are
 * pronounceable and stable for a given seed ("Tavros", "Kelune", "Oriveth").
 */
public final class PlanetNames {
	private static final String[] START = {"K", "T", "V", "R", "S", "M", "N", "D", "Th", "Kr", "Br", "Z", "L", "Or", "Ar", "El", "Is", "H", "C", "G"};
	private static final String[] VOWEL = {"a", "e", "i", "o", "u", "ae", "y", "ai", "eo"};
	private static final String[] MIDDLE = {"r", "l", "n", "v", "th", "s", "rr", "x", "m", "d", "k", "lv", "nd", "st"};
	private static final String[] END = {"", "", "n", "s", "th", "r", "x", "on", "is", "ar", "eth", "une", "os", "ia"};

	private PlanetNames() {}

	public static String generate(RandomSource random) {
		StringBuilder name = new StringBuilder();
		name.append(pick(random, START)).append(pick(random, VOWEL));
		int middles = random.nextInt(3) == 0 ? 2 : 1;
		for (int i = 0; i < middles; i++) name.append(pick(random, MIDDLE)).append(pick(random, VOWEL));
		name.append(pick(random, END));
		return name.toString();
	}

	private static String pick(RandomSource random, String[] options) {
		return options[random.nextInt(options.length)];
	}
}
