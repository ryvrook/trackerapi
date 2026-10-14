package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * Items left a player's own inventory, put into a container, dropped, eaten, placed or lost on death.
 *
 * <p>{@code atBlock} is true when the player used a container block in the last 30 seconds, and the block
 * coordinates then name it, so a consumer can tell which chest the items went into or came out of.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemsLost(Header header, String itemId, long quantity, How how, boolean atBlock, int blockX, int blockY, int blockZ) implements TrackerEvent {

    public enum How { STASHED, DROPPED, CONSUMED, PLACED, DEATH, OTHER }

    @Override
    public EventKind kind() {
        return EventKind.ITEMS_LOST;
    }
}
