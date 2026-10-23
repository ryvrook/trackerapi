package dev.ryvrook.trackerapi.mods.arcanum;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.UUID;

/**
 * The engine behind {@link ArcanumEngine}. The cast listener is an {@link ArcanumCastSystem} registered straight
 * on {@code EntityStore.REGISTRY} at bind and removed by class at unbind, since the registry refuses a second
 * system of the same class and the rebind after an Arcanum reload needs a fresh one. Every removal checks
 * {@code hasSystemClass} first, because {@code unregisterSystem} of a class the registry does not hold fails while
 * it holds its write lock. The reads post to the world thread, the poll through a {@link WorldTasks.Gate} so a world
 * that fell behind holds at most one waiting read. Never throws from a read.
 */
final class LiveArcanumEngine implements ArcanumEngine {
    static final LiveArcanumEngine LIVE = new LiveArcanumEngine();

    private final WorldTasks.Gate gate = new WorldTasks.Gate(10_000);

    private LiveArcanumEngine() {
    }

    @Override
    public Runnable listen(Class<?> eventClass, Object preparedType, CastListener listener) {
        ComponentRegistry<EntityStore> registry = EntityStore.REGISTRY;
        // A system left by a bind whose undo could not run, closed already, goes first.
        removeSystem(registry);
        ArcanumCastSystem system = new ArcanumCastSystem(eventClass, preparedType, listener);
        try {
            registry.registerSystem(system);
        } catch (RuntimeException | LinkageError e) {
            // The engine may fail after adding the system while it rebuilds its order, as Registrar knows.
            if (!holds(registry)) {
                system.close();
                throw e;
            }
        }
        return () -> {
            system.close();
            removeSystem(registry);
        };
    }

    private static boolean holds(ComponentRegistry<EntityStore> registry) {
        try {
            return registry.hasSystemClass(ArcanumCastSystem.class);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static void removeSystem(ComponentRegistry<EntityStore> registry) {
        try {
            if (!registry.isShutdown() && registry.hasSystemClass(ArcanumCastSystem.class)) {
                registry.unregisterSystem(ArcanumCastSystem.class);
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e); // a registry shutting down under the call
        }
    }

    @Override
    public void readAll(Reader reader) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            gate.sweep();
            for (World world : universe.getWorlds().values()) {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                gate.post(world, () -> visitWorld(world, reader));
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    @Override
    public void read(UUID uuid, Reader reader) {
        if (uuid == null) {
            return;
        }
        try {
            Universe universe = Universe.get();
            PlayerRef pr = universe == null ? null : universe.getPlayer(uuid);
            if (pr == null) {
                return;
            }
            UUID worldId = pr.getWorldUuid();
            World world = worldId == null ? null : universe.getWorld(worldId);
            if (world == null) {
                return;
            }
            WorldTasks.post(world, () -> {
                try {
                    visit(pr, world.getEntityStore().getStore(), reader);
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private static void visitWorld(World world, Reader reader) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (PlayerRef pr : world.getPlayerRefs()) {
                visit(pr, store, reader);
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private static void visit(PlayerRef pr, Store<EntityStore> store, Reader reader) {
        if (pr == null) {
            return;
        }
        try {
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) {
                return; // between worlds, read where they land
            }
            reader.read(pr.getUuid(), ref, store);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }
}
