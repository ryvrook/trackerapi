package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A status effect started or ended on a player.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record EffectChanged(Header header, String effectId, boolean applied) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.EFFECT_CHANGED;
    }
}
