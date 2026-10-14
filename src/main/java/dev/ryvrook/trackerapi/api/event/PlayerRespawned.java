package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player respawned after dying.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerRespawned(Header header, double x, double y, double z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_RESPAWNED;
    }
}
