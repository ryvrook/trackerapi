package dev.ryvrook.trackerapi.facts;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last block each player used, with its position and when, so an inventory change that moves items
 * to or from another container in the next 30 seconds can name the block. One small record per use,
 * nothing allocated on the read path. Safe from any thread.
 */
public final class RecentContainers {
    /** How long a block use explains the container moves after it. */
    public static final long WINDOW_NANOS = 30_000_000_000L;

    /** One block use. */
    public record Use(String world, int x, int y, int z, long atNanos) {
    }

    private final Map<UUID, Use> last = new ConcurrentHashMap<>();

    public RecentContainers registerWith(Forgetting forgetting) {
        forgetting.onPlayer(this::forget);
        forgetting.onWorld(this::forgetWorld);
        return this;
    }

    public void remember(UUID uuid, String world, int x, int y, int z) {
        remember(uuid, world, x, y, z, System.nanoTime());
    }

    public void remember(UUID uuid, String world, int x, int y, int z, long nowNanos) {
        if (uuid != null) {
            last.put(uuid, new Use(world, x, y, z, nowNanos));
        }
    }

    /** The player's last block use if it was within the window, else null. */
    public Use recent(UUID uuid) {
        return recent(uuid, System.nanoTime());
    }

    public Use recent(UUID uuid, long nowNanos) {
        if (uuid == null) {
            return null;
        }
        Use use = last.get(uuid);
        if (use == null) {
            return null;
        }
        if (nowNanos - use.atNanos() > WINDOW_NANOS) {
            last.remove(uuid, use);
            return null;
        }
        return use;
    }

    public void forget(UUID uuid) {
        if (uuid != null) {
            last.remove(uuid);
        }
    }

    public void forgetWorld(String world) {
        if (world != null) {
            last.values().removeIf(use -> world.equals(use.world()));
        }
    }
}
