package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player crafted a recipe. {@code quantity} is how many times the recipe ran, {@code itemId} the primary output.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ItemCrafted(Header header, String recipeId, String itemId, long quantity) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_CRAFTED;
    }
}
