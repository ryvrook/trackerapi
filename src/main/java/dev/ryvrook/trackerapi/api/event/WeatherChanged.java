package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * The weather of a world changed, as the once a second poll saw it.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record WeatherChanged(Header header, String weatherId, String previousWeatherId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.WEATHER_CHANGED;
    }
}
