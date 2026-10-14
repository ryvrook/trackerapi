package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player recorded a Memory.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record MemoryRecorded(Header header, String memoryId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MEMORY_RECORDED;
    }
}
