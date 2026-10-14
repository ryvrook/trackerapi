package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A world event such as a blood moon or an invasion started.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WorldEventStarted(Header header, String eventId, java.util.UUID eventUuid, double x, double y, double z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.WORLD_EVENT_STARTED;
    }
}
