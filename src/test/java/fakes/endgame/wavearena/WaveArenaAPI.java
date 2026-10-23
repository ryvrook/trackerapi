package fakes.endgame.wavearena;

import com.hypixel.hytale.registry.Registration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The static trial API of the fake mod. The statics live per class loader, as the real ones live in the mod's
 * loader, so a test reaches them through the isolating loader. The fire methods are the test side.
 */
public final class WaveArenaAPI {
    private static volatile boolean available = true;
    private static final List<WaveArenaCallbacks> CALLBACKS = new CopyOnWriteArrayList<>();

    private WaveArenaAPI() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static Registration registerCallbacks(WaveArenaCallbacks callbacks) {
        if (!available) {
            return new Registration(() -> false, () -> {
            });
        }
        CALLBACKS.add(callbacks);
        return new Registration(() -> true, () -> CALLBACKS.remove(callbacks));
    }

    public static boolean isInArena(UUID owner) {
        return false;
    }

    // ---- test side ---------------------------------------------------------------------------------------

    public static void setAvailable(boolean value) {
        available = value;
    }

    public static int callbackCount() {
        return CALLBACKS.size();
    }

    public static void fireCountdown(UUID owner, String arenaId, int seconds) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onCountdown(owner, arenaId, seconds);
        }
    }

    public static void fireWaveStart(UUID owner, String arenaId, int wave, int totalWaves) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onWaveStart(owner, arenaId, wave, totalWaves);
        }
    }

    public static void fireWaveClear(UUID owner, String arenaId, int wave, int totalWaves) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onWaveClear(owner, arenaId, wave, totalWaves);
        }
    }

    public static void fireCompleted(UUID owner, String arenaId, int waves) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onArenaCompleted(owner, arenaId, waves);
        }
    }

    public static void fireFailed(UUID owner, String arenaId, int wave, String reason) {
        WaveArenaCallbacks.FailReason r = WaveArenaCallbacks.FailReason.valueOf(reason);
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onArenaFailed(owner, arenaId, wave, r);
        }
    }

    public static void fireMobKilled(UUID owner, String arenaId) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onMobKilled(null, owner, arenaId);
        }
    }

    public static void fireCleanup(UUID owner) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onCleanup(owner);
        }
    }

    public static void fireOwnerChanged(UUID oldOwner, UUID newOwner, String arenaId) {
        for (WaveArenaCallbacks c : CALLBACKS) {
            c.onOwnerChanged(oldOwner, newOwner, arenaId);
        }
    }
}
