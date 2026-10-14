package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player entered a world that is not the one they were in. {@code fromWorld} is null on the first world of a session, {@code portalTypeId} names the portal type for an instance world.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerWorldChanged(Header header, String fromWorld, String portalTypeId, boolean instance) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_WORLD_CHANGED;
    }
}
