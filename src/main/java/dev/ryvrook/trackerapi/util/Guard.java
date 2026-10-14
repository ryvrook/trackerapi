package dev.ryvrook.trackerapi.util;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Hooks run inside the world tick, so a failure is logged and swallowed instead of reaching the engine.
 * Logged at most once a minute per guard so a broken hook cannot flood the console.
 */
public final class Guard {
    private static final long QUIET_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicLong LAST = new AtomicLong(System.nanoTime() - QUIET_NANOS);

    private Guard() {
    }

    /** Logs a hook failure, at most once a minute. */
    public static void failed(Throwable cause) {
        failed(cause, "TrackerAPI skipped an event after an error");
    }

    public static void failed(Throwable cause, String message) {
        long now = System.nanoTime();
        long last = LAST.get();
        if (now - last >= QUIET_NANOS && LAST.compareAndSet(last, now)) {
            LOGGER.at(Level.WARNING).withCause(cause).log(message);
        }
    }

    /** A per instance limiter, one line a minute per key, for listener failures and budget warnings. */
    public static final class RateLimiter {
        private final java.util.concurrent.ConcurrentHashMap<String, Long> last = new java.util.concurrent.ConcurrentHashMap<>();
        private final long quietNanos;
        private static final int MAX_KEYS = 512;

        public RateLimiter(long quietNanos) {
            this.quietNanos = quietNanos;
        }

        public boolean allow(String key) {
            long now = System.nanoTime();
            Long prev = last.get(key);
            if (prev != null && now - prev < quietNanos) {
                return false;
            }
            if (prev == null && last.size() >= MAX_KEYS) {
                last.clear();
            }
            last.put(key, now);
            return true;
        }
    }
}
