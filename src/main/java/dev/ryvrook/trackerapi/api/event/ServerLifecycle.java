package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * TrackerAPI started or is stopping. Consumers that keep state can flush on STOPPING.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ServerLifecycle(Header header, Phase phase) implements TrackerEvent {

    public enum Phase { STARTED, STOPPING }

    @Override
    public EventKind kind() {
        return EventKind.SERVER_LIFECYCLE;
    }
}
