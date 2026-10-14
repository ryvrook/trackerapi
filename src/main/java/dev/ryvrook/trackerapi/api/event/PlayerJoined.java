package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player is ready in their first world of a session. {@code firstEver} is true when TrackerAPI has no file for them.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerJoined(Header header, String name, boolean firstEver) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_JOINED;
    }
}
