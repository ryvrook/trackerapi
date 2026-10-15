package dev.ryvrook.trackerapi.facts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Items each player let go of in a recent window, by item id and count, oldest first. Taking the same
 * items back takes from here first, so a stack put down and picked up again is not counted as new. The
 * Feats {@code RecentDrops} shape, shared by {@link RecentDrops} and {@link RecentStash}. Safe from any
 * thread, each player's map is guarded by itself.
 */
public abstract class ItemLedger {
    private final long windowNanos;
    private final int maxEntries;

    private static final class Entry {
        long amount;
        final long at;

        Entry(long amount, long at) {
            this.amount = amount;
            this.at = at;
        }
    }

    private final Map<UUID, Map<String, Deque<Entry>>> ledger = new ConcurrentHashMap<>();

    ItemLedger(long windowNanos, int maxEntries) {
        this.windowNanos = windowNanos;
        this.maxEntries = maxEntries;
    }

    public void record(UUID uuid, String itemId, long amount) {
        record(uuid, itemId, amount, System.nanoTime());
    }

    public void record(UUID uuid, String itemId, long amount, long nowNanos) {
        if (uuid == null || itemId == null || amount <= 0) {
            return;
        }
        Map<String, Deque<Entry>> mine = ledger.computeIfAbsent(uuid, id -> new HashMap<>());
        synchronized (mine) {
            prune(mine, nowNanos);
            if (size(mine) >= maxEntries) {
                return; // memory stays bounded, a flood past the cap is not remembered
            }
            mine.computeIfAbsent(itemId, id -> new ArrayDeque<>()).addLast(new Entry(amount, nowNanos));
        }
    }

    /** Takes up to {@code amount} of this item from the player's ledger and returns how much was taken. */
    public long take(UUID uuid, String itemId, long amount) {
        return take(uuid, itemId, amount, System.nanoTime());
    }

    public long take(UUID uuid, String itemId, long amount, long nowNanos) {
        if (uuid == null || itemId == null || amount <= 0) {
            return 0;
        }
        Map<String, Deque<Entry>> mine = ledger.get(uuid);
        if (mine == null) {
            return 0;
        }
        synchronized (mine) {
            prune(mine, nowNanos);
            Deque<Entry> queue = mine.get(itemId);
            if (queue == null) {
                return 0;
            }
            long taken = 0;
            while (taken < amount && !queue.isEmpty()) {
                Entry entry = queue.peekFirst();
                long part = Math.min(amount - taken, entry.amount);
                entry.amount -= part;
                taken += part;
                if (entry.amount <= 0) {
                    queue.removeFirst();
                }
            }
            if (queue.isEmpty()) {
                mine.remove(itemId);
            }
            return taken;
        }
    }

    public void forget(UUID uuid) {
        if (uuid != null) {
            ledger.remove(uuid);
        }
    }

    /** Entries the player has in the ledger right now, for tests. */
    public int entries(UUID uuid) {
        Map<String, Deque<Entry>> mine = uuid == null ? null : ledger.get(uuid);
        if (mine == null) {
            return 0;
        }
        synchronized (mine) {
            return size(mine);
        }
    }

    private void prune(Map<String, Deque<Entry>> mine, long now) {
        Iterator<Deque<Entry>> it = mine.values().iterator();
        while (it.hasNext()) {
            Deque<Entry> queue = it.next();
            while (!queue.isEmpty() && now - queue.peekFirst().at > windowNanos) {
                queue.removeFirst();
            }
            if (queue.isEmpty()) {
                it.remove();
            }
        }
    }

    private static int size(Map<String, Deque<Entry>> mine) {
        int n = 0;
        for (Deque<Entry> queue : mine.values()) {
            n += queue.size();
        }
        return n;
    }
}
