package dev.ryvrook.trackerapi.mods.mmo;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The package roots and engine seams of the MMO Skill Tree binding. {@link #LIVE} names the real mod and the real
 * engine. A test gives the fake packages, a bus that keeps the consumers in a map and worlds that hand the reader
 * stand in stores and refs on the calling thread, so the same binding code runs against the fake and the jar.
 */
public record MmoNames(String mod, String common, Bus bus, Worlds worlds) {
    /** The real mod, the real common library and the engine. */
    public static final MmoNames LIVE = new MmoNames("com.ziggfreed.mmoskilltree", "com.ziggfreed.common", null, MmoWorlds.LIVE);

    /**
     * A stand in for the engine's event bus. Null in production, where the binding registers through TrackerAPI's
     * own event registry.
     */
    public interface Bus {
        /** Registers for an event class and returns the step that unregisters it. */
        Runnable listen(Class<?> eventClass, Consumer<Object> consumer);
    }

    /** Reads of player entities on their world threads, and the player behind an entity ref. */
    public interface Worlds {
        /** A read of one online player's entity, on the thread of the world that owns it. */
        interface Reader {
            void read(UUID uuid, Object store, Object ref) throws Throwable;
        }

        /** Posts the reader for every online player to each world's thread. Returns at once. */
        void readAll(Reader reader);

        /** Posts the reader for one online player to the world's thread. Does nothing for a player who is gone. */
        void read(UUID uuid, Reader reader);

        /** The uuid of the player an entity ref stands for, or null for anything that is not a player. World thread. */
        UUID playerOf(Object ref);
    }
}
