package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player ran a command. {@code command} is the first word without the slash, arguments are never recorded.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerCommand(Header header, String name, String command) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_COMMAND;
    }
}
