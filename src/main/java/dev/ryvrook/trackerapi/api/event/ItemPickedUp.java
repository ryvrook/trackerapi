package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player picked an item up from the ground by hand. {@code fresh} is the part of the quantity the player had not dropped themself in the last five minutes.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemPickedUp(Header header, String itemId, long quantity, long fresh) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_PICKED_UP;
    }
}
