package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player tamed an animal by feeding it.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record CreatureTamed(Header header, String npcTypeId, java.util.UUID entityUuid, String tamedTypeId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.CREATURE_TAMED;
    }
}
