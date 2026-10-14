package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A world event such as a blood moon or an invasion ended. {@code outcome} is the engine's name for how it ended. {@code eventUuid} is the world event's own uuid, the one its {@link WorldEventStarted} carries when the event had one, so an end can be paired with its start. It is null when the engine gave none.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WorldEventEnded(Header header, String eventId, String outcome, java.util.List<java.util.UUID> participants, java.util.UUID eventUuid) implements TrackerEvent {

    /** The record without {@code eventUuid}, which is then null. */
    public WorldEventEnded(Header header, String eventId, String outcome, java.util.List<java.util.UUID> participants) {
        this(header, eventId, outcome, participants, null);
    }

    @Override
    public EventKind kind() {
        return EventKind.WORLD_EVENT_ENDED;
    }
}
