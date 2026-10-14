package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player was teleported within a world, by a command, a warp or the engine.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerTeleported(Header header, double fromX, double fromY, double fromZ, double x, double y, double z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_TELEPORTED;
    }
}
