package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player switched hotbar slot. {@code itemId} is what is now held, or null. High volume.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ActiveSlotChanged(Header header, int fromSlot, int toSlot, String itemId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ACTIVE_SLOT_CHANGED;
    }
}
