package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A mod ability was cast or a relic power used. {@code itemId} is the item that carried it or null, {@code targetTypeId}
 * the type of the entity it hit or null.
 */
public record ModAbilityUsed(Header header, String mod, String abilityId, String itemId, String targetTypeId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_ABILITY_USED;
    }
}
