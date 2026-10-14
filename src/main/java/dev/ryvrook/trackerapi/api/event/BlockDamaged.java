package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player hit a block without breaking it yet. {@code damage} is this hit, {@code total} the block's damage after it. Fires on every swing, so it is high volume.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record BlockDamaged(Header header, int x, int y, int z, String blockId, String toolItemId, float damage, float total) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.BLOCK_DAMAGED;
    }
}
