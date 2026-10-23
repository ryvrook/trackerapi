package dev.ryvrook.trackerapi.mods.rpg;

import java.util.UUID;

/**
 * Walks the online players for the RPG Leveling reads, so the binding runs without an engine in tests. The engine
 * version is {@link EngineRpgPlayers}. Both methods return without waiting, the visitor runs later on the world
 * thread that owns the player, with the player's {@code PlayerRef}, entity {@code Ref} and entity {@code Store} as
 * plain objects, since the binding hands them straight to the mod.
 */
interface RpgPlayers {
    @FunctionalInterface
    interface Visitor {
        void visit(UUID uuid, Object playerRef, Object ref, Object store);
    }

    /** Every online player, one task per world with players. */
    void forEachOnline(Visitor visitor);

    /** One player, a task on their world, nothing when they are not in a world. */
    void one(UUID uuid, Visitor visitor);
}
