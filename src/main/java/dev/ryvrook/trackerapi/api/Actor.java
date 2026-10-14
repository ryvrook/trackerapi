package dev.ryvrook.trackerapi.api;

import java.util.UUID;

/**
 * Who did something or had something done to them. A player carries their account uuid and name. A
 * creature carries its NPC type id, such as {@code Goblin_Scrapper}, and the uuid of that one entity when
 * the engine gave it one, which multi phase bosses keep across their phases. Anything else, a block, the
 * world, a projectile with no owner, is {@link Kind#WORLD}.
 *
 * <p>Immutable. Part of the stable TrackerAPI contract.
 */
public record Actor(Kind kind, UUID uuid, String name, String typeId) {

    public enum Kind { PLAYER, CREATURE, WORLD }

    /** The world itself, for damage with no attacker and the like. */
    public static final Actor WORLD = new Actor(Kind.WORLD, null, null, null);

    public static Actor player(UUID uuid, String name) {
        return new Actor(Kind.PLAYER, uuid, name, "Player");
    }

    public static Actor creature(UUID entityUuid, String npcTypeId) {
        return new Actor(Kind.CREATURE, entityUuid, null, npcTypeId);
    }

    public boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    public boolean isCreature() {
        return kind == Kind.CREATURE;
    }

    /** The player's uuid, or null when this actor is not a player. */
    public UUID playerUuid() {
        return kind == Kind.PLAYER ? uuid : null;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case PLAYER -> "player " + name + " (" + uuid + ")";
            case CREATURE -> "creature " + typeId + (uuid != null ? " (" + uuid + ")" : "");
            case WORLD -> "world";
        };
    }
}
