package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A world event received a signal, such as a stage being cleared.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WorldEventSignalled(Header header, java.util.UUID eventUuid, String signalId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.WORLD_EVENT_SIGNALLED;
    }
}
