package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * A player or creature died. {@code player()} is the victim when the victim is a player, else the killer when the killer is one. {@code participants} lists every player who hit a boss in the last minute of the fight, the killer first, and {@code untouched} those among them who took no damage since the fight began. Both are empty for anything that is not a boss.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record EntityDied(Header header, Actor victim, Actor killer, String causeId, String effectId, boolean ranged, boolean viaEffect, double x, double y, double z, boolean boss, java.util.List<java.util.UUID> participants, java.util.Set<java.util.UUID> untouched, boolean killerArmourless, String portalTypeId, int itemsLost) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ENTITY_DIED;
    }
}
