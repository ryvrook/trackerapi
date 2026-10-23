package dev.ryvrook.trackerapi.mods.enchant;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.registry.Registration;
import dev.ryvrook.trackerapi.mods.ModBindings;

import java.util.function.Consumer;

/**
 * The seam to the engine's event bus for events a mod dispatches as {@code IEvent} instances. The production
 * implementation registers through TrackerAPI's own {@link EventRegistry} with {@code registerGlobal}, the way the
 * mod itself and TrackerAPI's player hooks register for {@code IEvent<Void>} events, so the registration is owned
 * by TrackerAPI and dies with it. A test implementation captures the consumers and fires fake events.
 */
public interface EngineBus {
    /**
     * Listens to every dispatch of {@code eventClass}, a class from the mod's loader. Returns the step that
     * undoes the registration.
     */
    Runnable listen(Class<?> eventClass, Consumer<Object> consumer);

    /** The engine, through {@link ModBindings#events()}. */
    EngineBus ENGINE = new Engine();

    /** The production implementation. */
    final class Engine implements EngineBus {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Runnable listen(Class<?> eventClass, Consumer<Object> consumer) {
            EventRegistry registry = ModBindings.events();
            if (registry == null) {
                throw new IllegalStateException("TrackerAPI's event registry is not available");
            }
            Registration reg = registry.registerGlobal((Class) eventClass, (Consumer) consumer);
            return reg::unregister;
        }
    }
}
