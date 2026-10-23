package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A dungeon run of a mod changed phase for this player. {@code mode} is the mod's difficulty name or null,
 * {@code partySize} 0 when unknown, {@code durationMillis} the run time at COMPLETED or FAILED, else 0.
 */
public record DungeonRun(Header header, String mod, String dungeonId, Phase phase, String mode, int partySize, long durationMillis) implements TrackerEvent {

    public enum Phase { ENTERED, COMPLETED, FAILED, LEFT }

    @Override
    public EventKind kind() {
        return EventKind.DUNGEON_RUN;
    }
}
