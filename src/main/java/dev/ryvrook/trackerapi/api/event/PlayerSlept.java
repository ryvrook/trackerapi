package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player fell asleep in a bed or woke up.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerSlept(Header header, boolean asleep, int x, int y, int z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_SLEPT;
    }
}
