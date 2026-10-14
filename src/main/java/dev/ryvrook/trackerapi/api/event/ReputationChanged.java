package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player's reputation with a group changed.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ReputationChanged(Header header, String groupId, int from, int to) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.REPUTATION_CHANGED;
    }
}
