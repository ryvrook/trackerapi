package dev.ryvrook.trackerapi.facts;

import com.hypixel.hytale.server.core.universe.world.World;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    }
}
