package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player learned a recipe.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record RecipeLearned(Header header, String recipeId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.RECIPE_LEARNED;
    }
}
