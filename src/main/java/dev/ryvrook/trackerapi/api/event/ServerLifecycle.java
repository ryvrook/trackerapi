package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * TrackerAPI started or is stopping. Consumers that keep state can flush on STOPPING. A {@code /plugin reload}
 * of TrackerAPI publishes both while the server keeps running, so a consumer that reports the server itself
 * going up or down checks {@code HytaleServer.get().isShuttingDown()} or {@code isBooting()} as well.
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
