package dev.ryvrook.trackerapi.api;

/**
 * The stat names the counter sink writes for the generic mod events, under each binding's prefix from
 * {@link Keys#MOD_PREFIXES}. A key is {@code Keys.of(mod, stat)} such as {@code rpg:level}, a per id key adds the id,
 * such as {@code dungeons:cleared:Crypt}. Every binding's {@link StatSpec}s name the same keys, this class is the
 * one place the names live.
 */
public final class ModKeys {
    private ModKeys() {
    }

    /** The track of the main level of a mod, the player's own, as opposed to a skill. */
    public static final String PLAYER_TRACK = "player";

    public static final String LEVEL = "level";
    public static final String LEVEL_UPS = "level_ups";
    public static final String MAX_LEVEL_REACHED = "max_level_reached";
    public static final String SKILL_LEVEL = "skill_level";
    public static final String SKILL_LEVEL_UPS = "skill_level_ups";
    public static final String XP = "xp";
    public static final String ABILITIES = "abilities";
    public static final String PVP = "pvp";
    public static final String RUNS = "runs";
    public static final String CLEARED = "cleared";
    public static final String CLEARED_MODE = "cleared_mode";
    public static final String FASTEST = "fastest";
    public static final String FAILED = "failed";
    public static final String BOSSES = "bosses";
    public static final String ITEMS = "items";
    public static final String HIGHEST_LEVEL = "highest_level";
    public static final String PROCS = "procs";
    public static final String VEINS = "veins";
    public static final String BLOCKS = "blocks";
    public static final String LARGEST = "largest";
    public static final String DROPS = "drops";
    public static final String STREAK_BEST = "streak_best";
    public static final String TAMED = "tamed";
    public static final String BRED = "bred";
    public static final String COMPANION_LEVEL_UPS = "companion_level_ups";
    public static final String COMPANION_LEVEL_BEST = "companion_level_best";
    public static final String COMPANIONS_LOST = "companions_lost";

    /**
     * The plural of a category or slot word, for the discovery total of {@code ModChoiceChanged} and
     * {@code ModMilestone}: {@code class} to {@code classes}, {@code bounty} to {@code bounties}, {@code quest} to
     * {@code quests}.
     */
    public static String plural(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        String w = word;
        if (w.endsWith("y") && w.length() > 1 && "aeiou".indexOf(w.charAt(w.length() - 2)) < 0) {
            return w.substring(0, w.length() - 1) + "ies";
        }
        if (w.endsWith("s") || w.endsWith("x") || w.endsWith("z") || w.endsWith("ch") || w.endsWith("sh")) {
            return w + "es";
        }
        return w + "s";
    }

    /** {@code mod:stat}. */
    public static String key(String mod, String stat) {
        return Keys.of(mod, stat);
    }

    /** {@code mod:stat:id}. */
    public static String key(String mod, String stat, String id) {
        return Keys.of(Keys.of(mod, stat), id);
    }
}
