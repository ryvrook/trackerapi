package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * An item in a player's inventory reached zero durability.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemBroke(Header header, String itemId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_BROKE;
    }
}
