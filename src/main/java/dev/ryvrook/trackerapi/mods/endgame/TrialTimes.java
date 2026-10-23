package dev.ryvrook.trackerapi.mods.endgame;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * When each trial owner started their current run, so the binding can give a duration at the end. The wave
 * arena callbacks carry no time, so the clock starts at the first wave and stops at completed or failed. A run
 * the binding joined in the middle has no start and ends with a duration of zero.
 */
final class TrialTimes {
    private final Map<UUID, Long> starts = new ConcurrentHashMap<>();
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** Replaces the clock, for tests. */
    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Marks the start of a run for the owner, replacing an older start. */
    void start(UUID owner) {
        if (owner != null) {
            starts.put(owner, clock.getAsLong());
        }
    }

    /** Ends the owner's run and returns its length in milliseconds, 0 when the start was not seen. */
    long end(UUID owner) {
        if (owner == null) {
            return 0L;
        }
        Long start = starts.remove(owner);
        if (start == null) {
            return 0L;
        }
        return Math.max(0L, clock.getAsLong() - start);
    }

    /** Drops the owner's start without a result, for a cleanup. */
    void forget(UUID owner) {
        if (owner != null) {
            starts.remove(owner);
        }
    }

    /** Moves a running start from one owner to another. */
    void move(UUID from, UUID to) {
        if (from == null || to == null) {
            return;
        }
        Long start = starts.remove(from);
        if (start != null) {
            starts.put(to, start);
        }
    }

    void clear() {
        starts.clear();
    }

    int size() {
        return starts.size();
    }
}
