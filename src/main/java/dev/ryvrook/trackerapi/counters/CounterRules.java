package dev.ryvrook.trackerapi.counters;

import dev.ryvrook.trackerapi.api.Keys;

import java.util.Map;
import java.util.Set;

/** Which keys are bests rather than sums, for merges and imports. */
public final class CounterRules {
    private CounterRules() {
    }

    private static final Set<String> MAX = Set.of(Keys.BIGGEST_HIT, Keys.KILL_STREAK_BEST, Keys.SESSION_LONGEST,
            Keys.LONGEST_FALL, Keys.HIGHEST_Y);
    private static final Set<String> MIN = Set.of(Keys.LOWEST_Y);

    /** The stat names of the mod counters that keep a best value, such as {@code rpg:level} or {@code vein:largest}. */
    private static final Set<String> MOD_MAX = Set.of("level", "skill_level", "largest", "highest_level", "streak_best",
            "companion_level_best");
    private static final Set<String> MOD_MIN = Set.of("fastest");

    public static boolean isMax(String key) {
        return MAX.contains(key) || (Keys.isMod(key) && (MOD_MAX.contains(modStat(key)) || modStat(key).endsWith("_best")));
    }

    public static boolean isMin(String key) {
        return MIN.contains(key) || (Keys.isMod(key) && MOD_MIN.contains(modStat(key)));
    }

    /**
     * The plural total of a mod discovery key, {@code endgame:bestiaries} for {@code endgame:bestiary:Kweebec}, or
     * null for a key that is not three levels deep under a mod prefix.
     */
    public static String modDiscoveryTotal(String key) {
        if (!Keys.isMod(key)) {
            return null;
        }
        int a = key.indexOf(':');
        int b = key.indexOf(':', a + 1);
        if (b < 0 || b == key.length() - 1) {
            return null;
        }
        return Keys.of(key.substring(0, a), dev.ryvrook.trackerapi.api.ModKeys.plural(key.substring(a + 1, b)));
    }

    /** The stat part of a mod key, {@code level} for {@code rpg:level} and for {@code mmo:level:Mining}. */
    static String modStat(String key) {
        int a = key.indexOf(':');
        int b = key.indexOf(':', a + 1);
        return b < 0 ? key.substring(a + 1) : key.substring(a + 1, b);
    }

    /** Per id prefixes that record a first sighting, set to one once, with the total each one feeds. */
    private static final Map<String, String> DISCOVERY = Map.of(Keys.P_ZONE, Keys.ZONES_DISCOVERED,
            Keys.P_MEMORY, Keys.MEMORIES, Keys.P_INSTANCE, Keys.INSTANCES_DISCOVERED,
            Keys.P_OBJECTIVE, Keys.OBJECTIVES_COMPLETED, Keys.P_RECIPE, Keys.RECIPES_KNOWN);

    /** True for a discovery prefix such as {@code zone}, whose per id keys merge as the larger, not a sum. */
    public static boolean isDiscovery(String prefix) {
        return prefix != null && DISCOVERY.containsKey(prefix);
    }

    /** The total a discovery prefix feeds, or null. */
    public static String discoveryTotal(String prefix) {
        return prefix == null ? null : DISCOVERY.get(prefix);
    }
}
