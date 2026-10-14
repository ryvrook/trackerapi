package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A creature was removed from a world for good, by death, despawn or a command. Chunk unloads do not count.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record CreatureRemoved(Header header, String npcTypeId, java.util.UUID entityUuid, double x, double y, double z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.CREATURE_REMOVED;
    }
}
