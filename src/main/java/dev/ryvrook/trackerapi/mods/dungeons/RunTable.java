package dev.ryvrook.trackerapi.mods.dungeons;

import dev.ryvrook.trackerapi.api.event.DungeonRun;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runs in progress, one entry per dungeon world name. A player's run starts at the first Entered of that
 * player in that world, later Entered events of the same player (a reconnect, a respawn inside the instance)
 * belong to the same run. The party size is the high water mark of distinct players who entered the world, as
 * the mod scales it. A world is forgotten when the last player inside it left, and everything is dropped at
 * unbind. Callbacks arrive on world threads and the publishing thread, so each world's state is guarded by its
 * own lock.
 */
final class RunTable {
    /** What an Entered event means for the player, {@code first} when it opens a new run. */
    record Entered(boolean first, int partySize) {
    }

    /** What a Completed or Exited event means for the player. {@code phase} is null when nothing is to be published. */
    record Outcome(DungeonRun.Phase phase, String mode, int partySize, long durationMillis) {
        static final Outcome NONE = new Outcome(null, null, 0, 0L);
    }

    private static final class WorldRun {
        final String dungeonId;
        final Map<UUID, Long> enteredAt = new HashMap<>();
        final Set<UUID> inside = new HashSet<>();
        final Set<UUID> died = new HashSet<>();
        final Set<UUID> completed = new HashSet<>();
        String mode;

        WorldRun(String dungeonId, String mode) {
            this.dungeonId = dungeonId;
            this.mode = mode;
        }
    }

    private final Map<String, WorldRun> worlds = new ConcurrentHashMap<>();

    Entered enter(String world, String dungeonId, String mode, UUID player, long now) {
        WorldRun run = worlds.computeIfAbsent(world, w -> new WorldRun(dungeonId, mode));
        synchronized (run) {
            if (mode != null) {
                run.mode = mode;
            }
            boolean first = run.enteredAt.putIfAbsent(player, now) == null;
            run.inside.add(player);
            return new Entered(first, run.enteredAt.size());
        }
    }

    Outcome complete(String world, String mode, UUID player, long now) {
        WorldRun run = worlds.get(world);
        if (run == null) {
            return new Outcome(DungeonRun.Phase.COMPLETED, mode, 0, 0L);
        }
        synchronized (run) {
            run.completed.add(player);
            run.died.remove(player);
            return new Outcome(DungeonRun.Phase.COMPLETED, mode != null ? mode : run.mode, run.enteredAt.size(),
                    duration(run, player, now));
        }
    }

    Outcome exit(String world, UUID player, long now) {
        WorldRun run = worlds.get(world);
        if (run == null) {
            return Outcome.NONE;
        }
        Outcome out;
        synchronized (run) {
            run.inside.remove(player);
            if (run.completed.contains(player)) {
                out = Outcome.NONE;
            } else {
                DungeonRun.Phase phase = run.died.remove(player) ? DungeonRun.Phase.FAILED : DungeonRun.Phase.LEFT;
                out = new Outcome(phase, run.mode, run.enteredAt.size(), duration(run, player, now));
            }
            if (run.inside.isEmpty()) {
                worlds.remove(world, run);
            }
        }
        return out;
    }

    /** Notes a death of a player inside a tracked world. True when the world was one. */
    boolean died(String world, UUID player) {
        if (world == null || player == null) {
            return false;
        }
        WorldRun run = worlds.get(world);
        if (run == null) {
            return false;
        }
        synchronized (run) {
            if (!run.inside.contains(player)) {
                return false;
            }
            run.died.add(player);
            return true;
        }
    }

    /** The dungeon id a tracked world belongs to, null when the world is not tracked. */
    String dungeonOf(String world) {
        WorldRun run = world == null ? null : worlds.get(world);
        return run == null ? null : run.dungeonId;
    }

    int size() {
        return worlds.size();
    }

    void clear() {
        worlds.clear();
    }

    private static long duration(WorldRun run, UUID player, long now) {
        Long start = run.enteredAt.get(player);
        return start == null ? 0L : Math.max(0L, now - start);
    }
}
