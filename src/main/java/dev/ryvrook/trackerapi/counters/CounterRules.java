package dev.ryvrook.trackerapi.counters;

import dev.ryvrook.trackerapi.api.Keys;

import java.util.Set;

/** Which keys are bests rather than sums, for merges and imports. */
public final class CounterRules {
    private CounterRules() {
    }

    private static final Set<String> MAX = Set.of(Keys.BIGGEST_HIT, Keys.KILL_STREAK_BEST, Keys.SESSION_LONGEST,
            Keys.LONGEST_FALL, Keys.HIGHEST_Y);
    private static final Set<String> MIN = Set.of(Keys.LOWEST_Y);

    public static boolean isMax(String key) {
        return MAX.contains(key);
    }

    public static boolean isMin(String key) {
        return MIN.contains(key);
    }
}
