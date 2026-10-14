package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player discovered a map zone.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ZoneDiscovered(Header header, String zone, String region) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ZONE_DISCOVERED;
    }
}
