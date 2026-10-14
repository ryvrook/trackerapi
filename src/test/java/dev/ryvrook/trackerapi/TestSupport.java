package dev.ryvrook.trackerapi;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.util.Perf;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Small pieces the tests share. Nothing here starts an engine. */
public final class TestSupport {
    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Runs every task on the calling thread, so file work happens inside the call that queued it. */
    public static final Executor DIRECT = Runnable::run;
    private static final AtomicLong SEQ = new AtomicLong();

    private TestSupport() {
    }

    public static Header header(String world, UUID player) {
        return new Header(System.currentTimeMillis(), SEQ.incrementAndGet(), world, player);
    }

    public static BlockBroken broken(UUID player, String blockId) {
        return new BlockBroken(header("default", player), 1, 2, 3, blockId, "Tool_Pickaxe_Iron", false, false);
    }

    /** A counter store over {@code dir} whose file work runs inline. */
    public static CounterStore store(Path dir) {
        return new CounterStore(dir, LOGGER, DIRECT, new Perf());
    }

    /** A config with the defaults, as a fresh install has. */
    public static TrackerConfig config() {
        return new TrackerConfig();
    }

    /** An executor that keeps what it is given until {@link #runAll()}, to hold a file read in flight. */
    public static final class Manual implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        public int pending() {
            synchronized (this) {
                return tasks.size();
            }
        }

        public void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = tasks.poll();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }
}
