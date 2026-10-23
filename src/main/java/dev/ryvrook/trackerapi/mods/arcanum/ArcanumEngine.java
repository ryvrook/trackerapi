package dev.ryvrook.trackerapi.mods.arcanum;

import java.util.UUID;

/**
 * The engine side of the Arcanum binding, so the binding runs without an engine in tests. The live version is
 * {@link LiveArcanumEngine}. The cast listener runs on the world thread inside the mod's own tick, the readers run
 * later on the world thread that owns the player. Neither method that posts waits for the read.
 */
public interface ArcanumEngine {
    /** The item id at an inventory section and slot of the casting player, read on the same world thread. */
    @FunctionalInterface
    interface Slots {
        String itemAt(int section, int slot);
    }

    /**
     * One outcome event of the mod on a player entity. {@code prepared} is the player's prepared cast component at
     * that moment, or null when it is gone or its type is unknown.
     */
    @FunctionalInterface
    interface CastListener {
        void onCast(Object event, UUID player, Object prepared, Slots slots);
    }

    /** A read of one online player's entity, with the entity {@code Ref} and the world's entity {@code Store}. */
    @FunctionalInterface
    interface Reader {
        void read(UUID uuid, Object ref, Object store);
    }

    /**
     * Listens to the mod's outcome event on player entities. {@code preparedType} is the component type of the
     * prepared cast, read inside the handler. Returns the step that removes the listener again.
     */
    Runnable listen(Class<?> eventClass, Object preparedType, CastListener listener);

    /** Every online player, at most one waiting task per world. */
    void readAll(Reader reader);

    /** One online player, a task on their world, nothing when they are not in a world. */
    void read(UUID uuid, Reader reader);
}
