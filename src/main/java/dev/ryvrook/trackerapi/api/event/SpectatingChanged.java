package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * A player started or stopped spectating.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record SpectatingChanged(Header header, boolean spectating, Actor target) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.SPECTATING_CHANGED;
    }
}
