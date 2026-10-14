package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player's connection was accepted and their entity loaded, before they are in a world. {@code world} is the world they will spawn in.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerConnected(Header header, String name) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_CONNECTED;
    }
}
