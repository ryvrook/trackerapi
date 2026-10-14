package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player ate a meal or drank a potion.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemConsumed(Header header, String itemId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_CONSUMED;
    }
}
