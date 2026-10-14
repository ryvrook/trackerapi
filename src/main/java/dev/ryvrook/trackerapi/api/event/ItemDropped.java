package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player dropped an item on the ground, with the drop key or by dying.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemDropped(Header header, String itemId, long quantity, boolean onDeath) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_DROPPED;
    }
}
