package dev.ryvrook.trackerapi.facts;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last block each player placed for a few seconds, ported from Feats. Placing a block takes
 * one item out of the hand the same way eating does, so the inventory hook asks here before it calls a
 * removal a meal or a potion, and calls it a placement instead. Safe from any thread.
 */
public final class RecentPlacements {
    /** How long a placement explains the next removal from the hand. */
    public static final long WINDOW_NANOS = 5_000_000_000L;

    private record Placement(String itemId, long at) {
    }

    private final Map<UUID, Placement> last = new ConcurrentHashMap<>();

    public RecentPlacements registerWith(Forgetting forgetting) {
        forgetting.onPlayer(this::forget);
        return this;
    }

    public void record(UUID uuid, String itemId) {
        record(uuid, itemId, System.nanoTime());
    }

    public void record(UUID uuid, String itemId, long nowNanos) {
        if (uuid != null && itemId != null) {
            last.put(uuid, new Placement(itemId, nowNanos));
        }
    }

    /** True once if the player just placed this item, consuming the record. */
    public boolean consume(UUID uuid, String itemId) {
        return consume(uuid, itemId, System.nanoTime());
    }

    public boolean consume(UUID uuid, String itemId, long nowNanos) {
        if (uuid == null || itemId == null) {
            return false;
        }
        Placement placement = last.get(uuid);
        if (placement == null || !placement.itemId().equals(itemId)) {
            return false;
        }
        last.remove(uuid, placement);
        return nowNanos - placement.at() <= WINDOW_NANOS;
    }

    public void forget(UUID uuid) {
        if (uuid != null) {
            last.remove(uuid);
        }
    }
}
