package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player's armour or tool slot changed. {@code itemId} is what is now in the slot, {@code previousItemId} what was there.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemEquipped(Header header, String section, int slot, String itemId, String previousItemId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_EQUIPPED;
    }
}
