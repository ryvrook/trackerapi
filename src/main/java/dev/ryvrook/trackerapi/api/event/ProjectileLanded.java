package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * A projectile hit something or landed. {@code hit} is the entity it struck, or null for the ground.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record ProjectileLanded(Header header, Actor shooter, String projectileId, Actor hit, double x, double y, double z) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.PROJECTILE_LANDED;
    }
}
