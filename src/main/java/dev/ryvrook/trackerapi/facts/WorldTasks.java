package dev.ryvrook.trackerapi.facts;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Posts work to a world's own thread. {@code World.execute} throws once a world stops taking tasks, which
 * portal and instance worlds do routinely while they close. Each post is guarded on its own, so a closing
 * world only skips itself and a loop over every world carries on with the next one. Any thread.
 */
public final class WorldTasks {
    private WorldTasks() {
    }

    /** Queues the task on the world's thread. Returns false when the world is null or refused the task. */
    public static boolean post(World world, Runnable task) {
        if (world == null || task == null) {
            return false;
        }
        try {
            world.execute(task);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Runs the task now when already on the world's thread, else posts it. */
    public static boolean run(World world, Runnable task) {
        if (world == null || task == null) {
            return false;
        }
        try {
            if (world.isInThread()) {
                task.run();
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        return post(world, task);
    }

    /** True while the world takes work, false for a world that stopped or is stopping. Never throws. */
    public static boolean isAlive(World world) {
        try {
            return world != null && world.isAlive();
        } catch (RuntimeException | LinkageError e) {
            return true; // unknown, let the post decide
        }
    }

    /**
     * True when the universe no longer holds this world under its name, so it was removed or replaced.
     * The universe keys its worlds by the lower case name, which {@code getWorld} applies. Never throws.
     */
    public static boolean isGone(World world) {
        if (world == null) {
            return true;
        }
        try {
            Universe universe = Universe.get();
            return universe != null && universe.getWorld(world.getName()) != world;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * At most one task of a poll in flight per world. A world thread that falls behind would otherwise
     * collect one queued sample per round and run them back to back once it catches up, which is exactly
     * when it can least afford it. {@link #post} skips the round while the previous task of the same world
     * has not run, and posts again once that task ran or is older than the stale bound, so a world that
     * closed without running it does not block the poll forever. Thread safe.
     */
    public static final class Gate {
        private final ConcurrentHashMap<World, Long> inFlight = new ConcurrentHashMap<>();
        private final long staleNanos;

        public Gate(long staleMillis) {
            this.staleNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, staleMillis));
        }

        /** Posts the task unless this world still has one waiting. Returns true when it was posted. */
        public boolean post(World world, Runnable task) {
            if (world == null || task == null) {
                return false;
            }
            if (!isAlive(world)) {
                return false; // a world being removed gets no entry that would hold it
            }
            long now = System.nanoTime();
            Long since = inFlight.get(world);
            if (since != null && now - since < staleNanos) {
                return false;
            }
            inFlight.put(world, now);
            boolean posted = WorldTasks.post(world, () -> {
                try {
                    task.run();
                } finally {
                    inFlight.remove(world);
                }
            });
            if (!posted) {
                inFlight.remove(world);
            }
            return posted;
        }

        public void forget(World world) {
            if (world != null) {
                inFlight.remove(world);
            }
        }

        /** Drops the entries of every world the test says is gone. Returns how many went. Any thread. */
        public int sweep(Predicate<World> gone) {
            int[] dropped = {0};
            inFlight.keySet().removeIf(w -> {
                if (gone.test(w)) {
                    dropped[0]++;
                    return true;
                }
                return false;
            });
            return dropped[0];
        }

        /** Drops the entries of worlds the universe no longer holds. */
        public int sweep() {
            return sweep(WorldTasks::isGone);
        }

        /** Entries held, for tests and status. */
        public int size() {
            return inFlight.size();
        }
    }
}
