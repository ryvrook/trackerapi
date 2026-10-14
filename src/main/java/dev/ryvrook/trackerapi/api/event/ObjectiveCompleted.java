package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player completed an objective.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ObjectiveCompleted(Header header, String objectiveId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.OBJECTIVE_COMPLETED;
    }
}
