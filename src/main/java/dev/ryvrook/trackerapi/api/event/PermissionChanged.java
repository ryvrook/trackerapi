package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player's permissions or groups changed. {@code change} says what, {@code values} lists the permissions or the one group.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PermissionChanged(Header header, Change change, java.util.List<String> values) implements TrackerEvent {

    public enum Change { PERMISSIONS_ADDED, PERMISSIONS_REMOVED, GROUP_ADDED, GROUP_REMOVED }

    @Override
    public EventKind kind() {
        return EventKind.PERMISSION_CHANGED;
    }
}
