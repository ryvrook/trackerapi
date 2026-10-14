package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player used a block, such as opening a chest, sleeping in a bed or using a bench.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record BlockUsed(Header header, int x, int y, int z, String blockId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.BLOCK_USED;
    }
}
