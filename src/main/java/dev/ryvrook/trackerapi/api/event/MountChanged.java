package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player mounted or dismounted something that moves.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record MountChanged(Header header, boolean mounted, String mountTypeId, java.util.UUID entityUuid) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOUNT_CHANGED;
    }
}
