package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * The once a minute sample of a world's clock, for consumers that time things by the in game day.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record GameTimeSampled(Header header, int hour, float dayProgress, int moonPhase, long gameDay) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.GAME_TIME_SAMPLED;
    }
}
