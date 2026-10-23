package dev.ryvrook.trackerapi.mods.parries;

import dev.ryvrook.trackerapi.api.Actor;

import java.util.function.Consumer;

/**
 * The engine side of the Perfect Parries binding, the event bus and the entity reads. {@link LiveEngine} is the
 * production one, the test gives one of its own since no engine runs there.
 */
interface Engine {
    /**
     * Listens for an event class of the mod on the engine bus. Returns the step that stops listening, or null when
     * the registration undoes itself at unbind.
     */
    Runnable listen(Class<?> eventClass, Consumer<Object> consumer);

    /**
     * Who an entity ref of the mod's event is. {@code anchor} is the ref whose world thread this callback runs on,
     * a ref from another store is answered as the world rather than read off its thread.
     */
    Actor actor(Object ref, Object anchor);
}
