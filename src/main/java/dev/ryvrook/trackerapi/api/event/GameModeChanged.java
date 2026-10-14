package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player's game mode changed.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record GameModeChanged(Header header, String from, String to) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.GAME_MODE_CHANGED;
    }
}
