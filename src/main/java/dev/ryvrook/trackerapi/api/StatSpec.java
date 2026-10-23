package dev.ryvrook.trackerapi.api;

/**
 * One statistic a mod binding writes as a counter, declared so a consumer can show it with a label and know how to
 * read it. {@code key} is the counter key, such as {@code rpg:level}. For a per id statistic {@code key} is the prefix
 * and the counters are {@code key:<id>}, with {@code idLabel} naming what the id is, such as "Dungeon". {@code unit}
 * is null, "millis", "metres" or "xp". {@code board} says TrackerAPI keeps a leaderboard for the key.
 */
public record StatSpec(String key, String label, Aggregate aggregate, boolean perId, String idLabel, boolean board,
                       String unit) {
    /** How the counter grows. SUM adds, MAX and MIN keep the best, COUNT counts distinct ids (a discovery). */
    public enum Aggregate { SUM, MAX, MIN, COUNT }

    public StatSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key");
        }
        label = label == null || label.isBlank() ? key : label;
        aggregate = aggregate == null ? Aggregate.SUM : aggregate;
    }

    public static StatSpec sum(String key, String label) {
        return new StatSpec(key, label, Aggregate.SUM, false, null, true, null);
    }

    public static StatSpec max(String key, String label) {
        return new StatSpec(key, label, Aggregate.MAX, false, null, true, null);
    }

    public static StatSpec min(String key, String label, String unit) {
        return new StatSpec(key, label, Aggregate.MIN, false, null, true, unit);
    }

    public static StatSpec count(String key, String label) {
        return new StatSpec(key, label, Aggregate.COUNT, false, null, true, null);
    }

    /** A per id statistic, {@code key:<id>} each, without a board. */
    public static StatSpec perId(String key, String label, String idLabel) {
        return new StatSpec(key, label, Aggregate.SUM, true, idLabel, false, null);
    }

    public StatSpec unit(String u) {
        return new StatSpec(key, label, aggregate, perId, idLabel, board, u);
    }

    public StatSpec noBoard() {
        return new StatSpec(key, label, aggregate, perId, idLabel, false, unit);
    }
}
