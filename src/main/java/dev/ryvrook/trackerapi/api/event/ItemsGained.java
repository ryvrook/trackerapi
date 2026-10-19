package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * Items landed in a player's own inventory from outside it, a pickup, loot, a crafted result or a container. Moves inside the player's own inventory never count. {@code fresh} is the part that is new to the player. Whatever the player put into another container or dropped and has not taken back yet is not fresh, with no time limit and across restarts. Items out of a container block some player placed are never fresh, except the output of a processing bench. {@code quantity} is always the full amount that moved.
 *
 * <p>{@code atBlock} is true when the player used a container block in the last 30 seconds, and the block
 * coordinates then name it, so a consumer can tell which chest the items went into or came out of.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemsGained(Header header, String itemId, long quantity, long fresh, Source source, boolean atBlock, int blockX, int blockY, int blockZ) implements TrackerEvent {

    public enum Source { PICKUP, CONTAINER, CRAFT, OTHER }

    @Override
    public EventKind kind() {
        return EventKind.ITEMS_GAINED;
    }
}
