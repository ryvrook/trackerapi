package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * An entity fired a projectile.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ProjectileLaunched(Header header, Actor shooter, String projectileId, boolean byWeapon) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PROJECTILE_LAUNCHED;
    }
}
