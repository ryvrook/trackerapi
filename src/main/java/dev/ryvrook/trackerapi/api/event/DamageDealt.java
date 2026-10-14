package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * One hit. {@code player()} is the attacker when the attacker is a player, else the victim when the victim is one. {@code ranged} is true for a projectile, {@code effectId} names the status effect when the damage came from one.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record DamageDealt(Header header, Actor attacker, Actor victim, double amount, String causeId, boolean ranged, String effectId, double x, double y, double z, boolean bossVictim) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.DAMAGE_DEALT;
    }
}
