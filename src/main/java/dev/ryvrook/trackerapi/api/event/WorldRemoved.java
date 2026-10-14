package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A world was removed from the universe.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WorldRemoved(Header header) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.WORLD_REMOVED;
    }
}
