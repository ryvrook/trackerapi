package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * The moon phase of a world changed.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record MoonPhaseChanged(Header header, int phase) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOON_PHASE_CHANGED;
    }
}
