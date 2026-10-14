package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player sent a chat message. {@code message} is null unless the owner turned on {@code chat.recordText}.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record PlayerChatted(Header header, String name, int length, boolean cancelled, String message) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PLAYER_CHATTED;
    }
}
