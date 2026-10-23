package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A vein mining mod broke a connected group of blocks at once. {@code blocks} counts the blocks the mod broke beyond
 * the one the player hit, which the engine counts as a normal break. {@code creative} is the flag of the break that
 * opened the vein, so the counters skip it as they skip creative breaks.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record VeinMined(Header header, String mod, String blockId, int blocks, boolean creative) implements TrackerEvent {

    /** The record without {@code creative}, which is then false. */
    public VeinMined(Header header, String mod, String blockId, int blocks) {
        this(header, mod, blockId, blocks, false);
    }

    @Override
    public EventKind kind() {
        return EventKind.VEIN_MINED;
    }
}
