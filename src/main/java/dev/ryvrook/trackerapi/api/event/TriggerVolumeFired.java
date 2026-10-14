package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * A trigger volume in the world fired. {@code type} is the engine's trigger event type, such as ENTER, EXIT or BLOCK_BROKEN, {@code entity} the entity involved when there is one.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record TriggerVolumeFired(Header header, String volumeId, String type, Actor entity) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.TRIGGER_VOLUME_FIRED;
    }
}
