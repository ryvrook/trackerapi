package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * The once a second position sample of a player who moved at least a quarter of a block. {@code metres} is the whole metres this sample added, {@code mode} how they moved. {@code zone} and {@code biome} are the player's current ones when known.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerMoved(Header header, double x, double y, double z, long metres, Mode mode, String zone, String biome) implements TrackerEvent {

    public enum Mode { WALK, SWIM, GLIDE, RIDE }

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_MOVED;
    }
}
