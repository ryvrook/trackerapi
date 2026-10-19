package dev.ryvrook.trackerapi.facts;

import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.counters.PlayerCounters;

import java.util.Map;
import java.util.UUID;

/**
 * Per player and per item, how much the player put into containers that are not their own or dropped, less
 * what they took back. Taking items out of a container or off the ground takes from here first, so moving a
 * stack out and in again is never a gain, however long it sat there. No clock, nothing expires. The balance
 * lives in the player's counters object and is saved in the player's file as {@code stash:<item id>}, so it
 * survives a leave, a reload and a restart. It is never a stat, see {@link PlayerCounters#stashPut}. A player
 * whose counters are not loaded has no balance. Safe from any thread.
 */
public final class StashBalance {
    private final CounterStore store;

    public StashBalance(CounterStore store) {
        this.store = store;
    }

    /** The player put this many of an item into a container that is not theirs, or dropped them. */
    public void put(UUID uuid, String itemId, long amount) {
        PlayerCounters pc = store.live(uuid);
        if (pc != null) {
            pc.stashPut(itemId, amount);
        }
    }

    /** Takes up to {@code amount} off the balance and returns how much was taken. */
    public long take(UUID uuid, String itemId, long amount) {
        PlayerCounters pc = store.live(uuid);
        return pc == null ? 0L : pc.stashTake(itemId, amount);
    }

    /**
     * The fresh part of a gain. Whatever the balance holds of the item is taken back first. Items out of a
     * container a player placed are never fresh, the balance is still taken down by them.
     */
    public long fresh(UUID uuid, String itemId, long amount, boolean placedContainer) {
        long taken = take(uuid, itemId, amount);
        return placedContainer ? 0L : Math.max(0L, amount - taken);
    }

    /**
     * Moves made in Creative. What went into another container adds to the balance and what came out of one
     * takes from it, and nothing is a gain. Either map may be null.
     */
    public void creative(UUID uuid, Map<String, Long> putAway, Map<String, Long> takenOut) {
        if (putAway != null) {
            putAway.forEach((id, amount) -> put(uuid, id, amount));
        }
        if (takenOut != null) {
            takenOut.forEach((id, amount) -> take(uuid, id, amount));
        }
    }

    /** The balance of one item, for tests and the status command. */
    public long balance(UUID uuid, String itemId) {
        PlayerCounters pc = store.live(uuid);
        return pc == null ? 0L : pc.stashed(itemId);
    }
}
