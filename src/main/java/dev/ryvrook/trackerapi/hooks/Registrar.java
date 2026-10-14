package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Guarded registration, the Chronicle EngineHooks recipe. Every system and listener is registered in its
 * own try, a failure logs one warning naming the feature and the rest carry on. Systems the engine added
 * but failed to record for the plugin (the fastutil iterator race during a reload on a busy server) are
 * remembered and removed at shutdown by {@link #unregisterStragglers()}.
 */
public final class Registrar {
    private final PluginBase plugin;
    private final HytaleLogger logger;
    private final List<String> registered = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final List<Class<?>> entityStragglers = new CopyOnWriteArrayList<>();
    private final List<Class<?>> chunkStragglers = new CopyOnWriteArrayList<>();

    public Registrar(PluginBase plugin, HytaleLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public EventRegistry events() {
        return plugin.getEventRegistry();
    }

    /** Registers an EntityStore system. */
    public void entitySystem(String feature, Supplier<? extends ISystem<EntityStore>> factory) {
        guard(feature, () -> registerEntity(feature, factory.get()));
    }

    /** Registers a ChunkStore system. */
    public void chunkSystem(String feature, Supplier<? extends ISystem<ChunkStore>> factory) {
        guard(feature, () -> registerChunk(feature, factory.get()));
    }

    /** Registers bus listeners through the plugin's event registry. */
    public void listener(String feature, Consumer<EventRegistry> registration) {
        guard(feature, () -> registration.accept(plugin.getEventRegistry()));
    }

    /** Anything else that may throw a LinkageError on an older server. */
    public void guard(String feature, Runnable action) {
        try {
            action.run();
            registered.add(feature);
        } catch (LinkageError | RuntimeException e) {
            failed.add(feature);
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI %s is not supported on this server", feature);
        }
    }

    private void registerEntity(String feature, ISystem<EntityStore> system) {
        ComponentRegistryProxy<EntityStore> ecs = plugin.getEntityStoreRegistry();
        Class<?> type = system.getClass();
        try {
            ecs.registerSystem(system);
            return;
        } catch (LinkageError | RuntimeException first) {
            if (entityHolds(type)) {
                entityStragglers.add(type);
                logger.at(Level.WARNING).log("TrackerAPI %s hit an engine error while the engine rebuilt its system order (%s) and is registered anyway", feature, first);
                return;
            }
        }
        try {
            ecs.registerSystem(system);
        } catch (LinkageError | RuntimeException second) {
            if (entityHolds(type)) {
                entityStragglers.add(type);
                return;
            }
            throw second;
        }
    }

    private void registerChunk(String feature, ISystem<ChunkStore> system) {
        ComponentRegistryProxy<ChunkStore> ecs = plugin.getChunkStoreRegistry();
        Class<?> type = system.getClass();
        try {
            ecs.registerSystem(system);
            return;
        } catch (LinkageError | RuntimeException first) {
            if (chunkHolds(type)) {
                chunkStragglers.add(type);
                logger.at(Level.WARNING).log("TrackerAPI %s hit an engine error while the engine rebuilt its system order (%s) and is registered anyway", feature, first);
                return;
            }
        }
        try {
            ecs.registerSystem(system);
        } catch (LinkageError | RuntimeException second) {
            if (chunkHolds(type)) {
                chunkStragglers.add(type);
                return;
            }
            throw second;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean entityHolds(Class<?> type) {
        try {
            return EntityStore.REGISTRY.hasSystemClass((Class) type);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean chunkHolds(Class<?> type) {
        try {
            return ChunkStore.REGISTRY.hasSystemClass((Class) type);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Shutdown, last step. Removes systems the engine holds without having recorded them for the plugin. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int unregisterStragglers() {
        int n = 0;
        for (Class<?> type : entityStragglers) {
            try {
                if (EntityStore.REGISTRY.hasSystemClass((Class) type)) {
                    EntityStore.REGISTRY.unregisterSystem((Class) type);
                    n++;
                }
            } catch (LinkageError | RuntimeException ignored) {
                // leave it to a restart
            }
        }
        for (Class<?> type : chunkStragglers) {
            try {
                if (ChunkStore.REGISTRY.hasSystemClass((Class) type)) {
                    ChunkStore.REGISTRY.unregisterSystem((Class) type);
                    n++;
                }
            } catch (LinkageError | RuntimeException ignored) {
                // leave it to a restart
            }
        }
        entityStragglers.clear();
        chunkStragglers.clear();
        return n;
    }

    public List<String> registered() {
        return registered;
    }

    public List<String> failed() {
        return failed;
    }
}
