package dev.ryvrook.trackerapi.mods.fish;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.registry.Registration;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.mods.ModBindings;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The seam to the engine for the Angler's Almanac binding: the event bus the mod dispatches its {@code IEvent}s on,
 * and the read of a player uuid from an entity {@code Ref}. The production implementation registers through
 * TrackerAPI's own {@link EventRegistry}, so the registrations die with TrackerAPI, and reads the {@link PlayerRef}
 * component off the ref's store. A test implementation captures the consumers and maps fake refs to uuids.
 */
public interface FishEngine {
    /**
     * Listens to every dispatch of {@code eventClass}, a class from the mod's loader, at the last priority when
     * {@code last} is set so a cancel by another listener is already decided. Returns the step that undoes it.
     */
    Runnable listen(Class<?> eventClass, boolean last, Consumer<Object> consumer);

    /**
     * The uuid of the player behind an entity ref, null for a ref that is not a player or no longer valid. Only on
     * the world thread that owns the ref.
     */
    UUID playerOf(Object ref);

    /** The engine. */
    FishEngine ENGINE = new Engine();

    /** The production implementation. */
    final class Engine implements FishEngine {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Runnable listen(Class<?> eventClass, boolean last, Consumer<Object> consumer) {
            EventRegistry registry = ModBindings.events();
            if (registry == null) {
                throw new IllegalStateException("TrackerAPI's event registry is not available");
            }
            Registration reg = last
                    ? registry.registerGlobal(EventPriority.LAST, (Class) eventClass, (Consumer) consumer)
                    : registry.registerGlobal((Class) eventClass, (Consumer) consumer);
            return reg::unregister;
        }

        @Override
        @SuppressWarnings("unchecked")
        public UUID playerOf(Object ref) {
            try {
                if (!(ref instanceof Ref<?> r) || !r.isValid()) {
                    return null;
                }
                Store<EntityStore> store = (Store<EntityStore>) r.getStore();
                if (store == null) {
                    return null;
                }
                PlayerRef pr = store.getComponent((Ref<EntityStore>) r, PlayerRef.getComponentType());
                return pr == null ? null : pr.getUuid();
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }
    }
}
