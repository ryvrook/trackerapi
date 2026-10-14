package dev.ryvrook.trackerapi.facts;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Running kills in a row per player, in memory only. A death or a disconnect ends the streak. */
public final class KillStreaks {
    private final Map<UUID, AtomicInteger> running = new ConcurrentHashMap<>();

    /** Notes a kill and returns the streak it made. */
    public int killed(UUID player) {
        if (player == null) {
            return 0;
        }
        return running.computeIfAbsent(player, p -> new AtomicInteger()).incrementAndGet();
    }

    public void died(UUID player) {
        if (player != null) {
            AtomicInteger s = running.get(player);
            if (s != null) {
                s.set(0);
            }
        }
    }

    public int current(UUID player) {
        AtomicInteger s = player == null ? null : running.get(player);
        return s == null ? 0 : s.get();
    }

    public void forget(UUID player) {
        if (player != null) {
            running.remove(player);
        }
    }
}
