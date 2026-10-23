package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A vein mining mod broke a connected group of blocks at once. {@code blocks} counts them, the first block included.
 */
public record VeinMined(Header header, String mod, String blockId, int blocks) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.VEIN_MINED;
    }
}
