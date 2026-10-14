package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A chunk entered a world's store. High volume.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ChunkLoaded(Header header, int chunkX, int chunkZ) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.CHUNK_LOADED;
    }
}
