package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player disconnected. {@code sessionMillis} is how long the session lasted.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerLeft(Header header, String name, long sessionMillis) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_LEFT;
    }
}
