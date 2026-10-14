package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player discovered an instance such as a dungeon.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record InstanceDiscovered(Header header, java.util.UUID instanceWorldUuid, String instanceId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.INSTANCE_DISCOVERED;
    }
}
