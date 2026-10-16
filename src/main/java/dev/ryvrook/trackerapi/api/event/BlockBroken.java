package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player broke a block. {@code playerPlaced} is true when the same player placed a block on this spot recently, which the anti farm rules of Feats and Chronicle skip. {@code toolItemId} is what was in the main hand, or null. {@code ripeCrop} is true when the block was a crop or sapling in its harvestable stage, which is a harvest by hand and counts even on a spot the player planted, since the crop had to grow first.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record BlockBroken(Header header, int x, int y, int z, String blockId, String toolItemId, boolean playerPlaced, boolean creative, boolean ripeCrop) implements TrackerEvent {

    /** The record without {@code ripeCrop}, which is then false. */
    public BlockBroken(Header header, int x, int y, int z, String blockId, String toolItemId, boolean playerPlaced,
                       boolean creative) {
        this(header, x, y, z, blockId, toolItemId, playerPlaced, creative, false);
    }

    @Override
    public EventKind kind() {
        return EventKind.BLOCK_BROKEN;
    }
}
