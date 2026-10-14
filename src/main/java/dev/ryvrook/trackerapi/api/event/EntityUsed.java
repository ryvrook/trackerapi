package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * A player used an entity, talked to an NPC, fed an animal, opened a vendor.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record EntityUsed(Header header, Actor target, String interactionType) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ENTITY_USED;
    }
}
