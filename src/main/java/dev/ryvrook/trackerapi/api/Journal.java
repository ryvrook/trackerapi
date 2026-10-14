package dev.ryvrook.trackerapi.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The on disk record of past events, when the owner keeps one. Every kind the config lists is appended as
 * one JSON line per event to hourly files under {@code mods/Ryvrook_TrackerAPI/journal/<world>/}, gzip
 * compressed once the hour closes, and pruned by age and by total size.
 *
 * <p>Queries scan the files that can hold matching events, newest first, on the {@code TrackerAPI-journal}
 * thread, and never touch a world thread. A query over a week of a busy server reads a few hundred megabytes
 * and takes seconds, so callers get a future and should keep {@code limit} small. At most four queries run
 * at once and the rest wait.
 */
public interface Journal {

    /** What to look for. Null or empty fields mean no restriction. Build one with {@link #query()}. */
    record Query(Set<EventKind> kinds, UUID player, String world, Integer minX, Integer minY, Integer minZ,
                 Integer maxX, Integer maxY, Integer maxZ, long fromMillis, long toMillis, int limit) {

        public Query withKinds(Set<EventKind> k) {
            return new Query(k, player, world, minX, minY, minZ, maxX, maxY, maxZ, fromMillis, toMillis, limit);
        }

        public Query withKind(EventKind k) {
            return withKinds(Set.of(k));
        }

        public Query withPlayer(UUID p) {
            return new Query(kinds, p, world, minX, minY, minZ, maxX, maxY, maxZ, fromMillis, toMillis, limit);
        }

        public Query withWorld(String w) {
            return new Query(kinds, player, w, minX, minY, minZ, maxX, maxY, maxZ, fromMillis, toMillis, limit);
        }

        /** Events whose position lies within {@code radius} blocks of x, y, z on every axis. */
        public Query near(int x, int y, int z, int radius) {
            return new Query(kinds, player, world, x - radius, y - radius, z - radius, x + radius, y + radius,
                    z + radius, fromMillis, toMillis, limit);
        }

        public Query between(long from, long to) {
            return new Query(kinds, player, world, minX, minY, minZ, maxX, maxY, maxZ, from, to, limit);
        }

        /** Only events observed in the last {@code millis}. */
        public Query last(long millis) {
            long now = System.currentTimeMillis();
            return between(now - millis, now);
        }

        public Query limit(int n) {
            return new Query(kinds, player, world, minX, minY, minZ, maxX, maxY, maxZ, fromMillis, toMillis, n);
        }

        public boolean hasBox() {
            return minX != null && maxX != null;
        }
    }

    /** An empty query over everything, limited to 100 results. */
    static Query query() {
        return new Query(null, null, null, null, null, null, null, null, null, 0L, Long.MAX_VALUE, 100);
    }

    /** One line read back from the journal. {@code json} is the line as written. */
    record Entry(EventKind kind, long at, long seq, String world, UUID player, String json) {
    }

    /** Runs the query on the journal thread. Newest events first. */
    CompletableFuture<List<Entry>> find(Query query);

    /** The kinds the journal records on this server. */
    Set<EventKind> kinds();

    /** The folder the journal writes to. */
    java.nio.file.Path folder();

    /** Bytes on disk across every journal file, as of the last prune. */
    long bytesOnDisk();
}
