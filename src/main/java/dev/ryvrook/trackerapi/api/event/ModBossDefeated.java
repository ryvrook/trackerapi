package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A boss of a mod went down, one event per participating player, in addition to the engine's {@link EntityDied}.
 * {@code dungeonId} is null outside a dungeon, {@code participants} every player credited.
 */
public record ModBossDefeated(Header header, String mod, String bossId, String dungeonId, java.util.List<java.util.UUID> participants) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_BOSS_DEFEATED;
    }
}
