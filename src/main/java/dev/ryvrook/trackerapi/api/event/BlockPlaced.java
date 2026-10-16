package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player placed a block. {@code itemId} is the item in hand, {@code blockId} the block that item turns into when the item knows it, else null. A seed bag has no block of its own, the engine places the block its place interaction names, so for a seed {@code blockId} is that block, such as {@code Plant_Crop_Carrot_Block} for {@code Plant_Seeds_Carrot}. {@code planted} is true when that block has farming data, a crop or a sapling. {@code repeatSpot} is true when the spot is one the player placed on recently, which the anti farm rules skip. For a planting it is only true when the player broke an unripe crop on that spot since planting there, so replanting a harvested plot counts and planting and breaking a seed over and over does not.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record BlockPlaced(Header header, int x, int y, int z, String itemId, String blockId, boolean repeatSpot, boolean creative, boolean planted) implements TrackerEvent {

    /** The record without {@code planted}, which is then false. */
    public BlockPlaced(Header header, int x, int y, int z, String itemId, String blockId, boolean repeatSpot,
                       boolean creative) {
        this(header, x, y, z, itemId, blockId, repeatSpot, creative, false);
    }

    @Override
    public EventKind kind() {
        return EventKind.BLOCK_PLACED;
    }
}
