package dev.ryvrook.trackerapi.mods.enchant;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The rate cap on enchantment activations, one pass per player and enchant id per {@value #WINDOW_MILLIS}
 * milliseconds. Simple Enchantments fires an activation per hit, per block damage tick and per heal tick, so the
 * callback asks this gate first and publishes only when it passes. The hot path allocates nothing after the first
 * sighting of a player and enchant id, the slots are {@link AtomicLong}s updated with a compare and set, and the
 * keys are the player's uuid and the mod's own id string.
 */
final class ProcGate {
    static final long WINDOW_MILLIS = 1000;
    private static final long NEVER = Long.MIN_VALUE;

    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, AtomicLong>> last = new ConcurrentHashMap<>();

    ProcGate(LongSupplier clock) {
        this.clock = clock;
    }

    /** True when this player's enchant id has not passed within the window, and marks the pass. */
    boolean pass(UUID player, String enchantId) {
        long now = clock.getAsLong();
        ConcurrentHashMap<String, AtomicLong> byEnchant = last.get(player);
        if (byEnchant == null) {
            byEnchant = last.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        }
        AtomicLong slot = byEnchant.get(enchantId);
        if (slot == null) {
            slot = byEnchant.computeIfAbsent(enchantId, k -> new AtomicLong(NEVER));
        }
        while (true) {
            long prev = slot.get();
            if (prev != NEVER && now - prev < WINDOW_MILLIS) {
                return false;
            }
            if (slot.compareAndSet(prev, now)) {
                return true;
            }
        }
    }

    /** Drops what is kept about a player who left. */
    void forget(UUID player) {
        if (player != null) {
            last.remove(player);
        }
    }

    /** Drops everything, at unbind. */
    void clear() {
        last.clear();
    }

    /** How many players have a slot, for tests. */
    int players() {
        return last.size();
    }
}
