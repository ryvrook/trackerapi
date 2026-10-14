package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player placed a block. {@code itemId} is the item in hand, {@code blockId} the block that item turns into when the item knows it, else null. {@code repeatSpot} is true when the spot is one the player placed on recently, which the anti farm rules skip.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record BlockPlaced(Header header, int x, int y, int z, String itemId, String blockId, boolean repeatSpot, boolean creative) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.BLOCK_PLACED;
    }
}
