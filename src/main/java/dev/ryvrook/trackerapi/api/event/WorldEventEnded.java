package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A world event such as a blood moon or an invasion ended. {@code outcome} is the engine's name for how it ended.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WorldEventEnded(Header header, String eventId, String outcome, java.util.List<java.util.UUID> participants) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.WORLD_EVENT_ENDED;
    }
}
