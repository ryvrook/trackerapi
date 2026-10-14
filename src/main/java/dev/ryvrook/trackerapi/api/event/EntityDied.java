package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.Actor;

/**
 * A player or creature died. {@code player()} is the victim when the victim is a player, else the killer when the killer is one. {@code participants} lists every player who hit a boss in the last minute of the fight, the killer first, and {@code untouched} those among them who took no damage since the fight began. Both are empty for anything that is not a boss. {@code armourless} lists the participants who were still in the world and wore no armour at the kill, the Feats hard mode challenge fact, and {@code killerArmourless} says the same for the killer alone.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record EntityDied(Header header, Actor victim, Actor killer, String causeId, String effectId, boolean ranged, boolean viaEffect, double x, double y, double z, boolean boss, java.util.List<java.util.UUID> participants, java.util.Set<java.util.UUID> untouched, boolean killerArmourless, String portalTypeId, int itemsLost, java.util.Set<java.util.UUID> armourless) implements TrackerEvent {

    /** The record without {@code armourless}, which is then empty. */
    public EntityDied(Header header, Actor victim, Actor killer, String causeId, String effectId, boolean ranged, boolean viaEffect,
                      double x, double y, double z, boolean boss, java.util.List<java.util.UUID> participants,
                      java.util.Set<java.util.UUID> untouched, boolean killerArmourless, String portalTypeId, int itemsLost) {
        this(header, victim, killer, causeId, effectId, ranged, viaEffect, x, y, z, boss, participants, untouched,
                killerArmourless, portalTypeId, itemsLost, java.util.Set.of());
    }

    /** Null sets read as empty. */
    public EntityDied {
        participants = participants == null ? java.util.List.of() : participants;
        untouched = untouched == null ? java.util.Set.of() : untouched;
        armourless = armourless == null ? java.util.Set.of() : armourless;
    }

    @Override
    public EventKind kind() {
        return EventKind.ENTITY_DIED;
    }
}
