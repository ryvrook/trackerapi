package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A block broke with no player behind it, from an explosion, fire, gravity or a creature. {@code cause} is the engine's name for it when known.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record BlockBrokenByWorld(Header header, int x, int y, int z, String blockId, String cause) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.BLOCK_BROKEN_BY_WORLD;
    }
}
