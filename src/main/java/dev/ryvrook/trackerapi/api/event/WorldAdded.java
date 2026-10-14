package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A world was added to the universe.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WorldAdded(Header header, boolean instance, String portalTypeId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.WORLD_ADDED;
    }
}
