package fakes.endgame.plugin.events.domain;

import java.util.Set;
import java.util.UUID;

/** The event marker of the fake mod's bus, with the four records the real mod publishes. */
public interface GameEvent {

    record BossKillEvent(Set<UUID> creditedPlayers, UUID killerUuid, String bossTypeId, String displayName,
                         long encounterDurationSeconds, Set<UUID> fallenPlayers) implements GameEvent {
    }

    record AchievementUnlockEvent(UUID playerUuid, String achievementId, String achievementName) implements GameEvent {
    }

    record DungeonEnterEvent(UUID playerUuid, String dungeonType) implements GameEvent {
    }

    record ComboTierChangeEvent(UUID playerUuid, int oldTier, int newTier, int comboCount) implements GameEvent {
    }
}
