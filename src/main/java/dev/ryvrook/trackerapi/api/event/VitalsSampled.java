package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * The periodic read of a player's health, stamina and mana. Off by default, see the {@code vitalsSeconds} setting.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record VitalsSampled(Header header, double health, double maxHealth, double stamina, double maxStamina, double mana, double maxMana) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.VITALS_SAMPLED;
    }
}
