package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A prefab was pasted into a world, by worldgen, a world event or a command.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PrefabPasted(Header header, int prefabId, double x, double y, double z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PREFAB_PASTED;
    }
}
