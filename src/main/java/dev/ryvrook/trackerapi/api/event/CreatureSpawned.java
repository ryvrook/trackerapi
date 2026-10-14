package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A creature was added to a world. {@code reason} is the engine's add reason, such as SPAWN or LOAD.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record CreatureSpawned(Header header, String npcTypeId, java.util.UUID entityUuid, double x, double y, double z, String reason) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.CREATURE_SPAWNED;
    }
}
