package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player started an interaction chain, an attack, a block use, an ability. {@code rootId} is the chain's root interaction id, {@code type} the engine's interaction type name. {@code affordable} is false for an ability whose cost the player could not pay.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record InteractionStarted(Header header, String rootId, String type, boolean affordable) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.INTERACTION_STARTED;
    }
}
