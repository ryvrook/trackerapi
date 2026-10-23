package fakes.endgame.wavearena;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/** The trial callbacks of the fake mod, seven abstract methods and four defaults as the real interface has. */
public interface WaveArenaCallbacks {
    void onCountdown(UUID owner, String arenaId, int seconds);

    void onWaveStart(UUID owner, String arenaId, int wave, int totalWaves);

    void onWaveClear(UUID owner, String arenaId, int wave, int totalWaves);

    default void onWaveTimedOut(UUID owner, String arenaId, int wave, int totalWaves) {
    }

    void onArenaCompleted(UUID owner, String arenaId, int waves);

    void onArenaFailed(UUID owner, String arenaId, int wave, FailReason reason);

    void onMobSpawned(Ref<EntityStore> mob, String arenaId, UUID owner, String npcTypeId, int wave);

    void onMobKilled(Ref<EntityStore> mob, UUID owner, String arenaId);

    default void onCleanup(UUID owner) {
    }

    default void onOwnerChanged(UUID oldOwner, UUID newOwner, String arenaId) {
    }

    default void onPlayerEnteredZone(UUID owner, String arenaId, UUID player) {
    }

    enum FailReason {
        PLAYER_DEATH, TIMEOUT, DISCONNECT, MANUAL, LEFT_ZONE, SPAWN_FAILED
    }
}
