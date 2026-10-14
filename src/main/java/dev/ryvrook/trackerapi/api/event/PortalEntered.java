package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player stepped into a portal block. The world change that follows arrives as PLAYER_WORLD_CHANGED.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PortalEntered(Header header, String portalTypeId, int x, int y, int z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PORTAL_ENTERED;
    }
}
