package dev.ryvrook.trackerapi.mods.mmo;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.facts.WorldTasks;

import java.util.UUID;

/**
 * The engine side of {@link MmoNames.Worlds}. Every read is posted to the world's own thread through
 * {@link WorldTasks}, the ref is checked against the world's store once there, and nothing of the engine is kept
 * past the task. Never throws.
 */
final class MmoWorlds implements MmoNames.Worlds {
    static final MmoWorlds LIVE = new MmoWorlds();

    private MmoWorlds() {
    }

    @Override
    public void readAll(Reader reader) {
        Universe universe;
        try {
            universe = Universe.get();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (universe == null) {
            return;
        }
        for (World world : universe.getWorlds().values()) {
            WorldTasks.post(world, () -> {
                try {
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    for (PlayerRef pr : world.getPlayerRefs()) {
                        readOne(pr, store, reader);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // a world closing under the read
                }
            });
        }
    }

    @Override
    public void read(UUID uuid, Reader reader) {
        try {
            Universe universe = Universe.get();
            if (universe == null || uuid == null) {
                return;
            }
            PlayerRef pr = universe.getPlayer(uuid);
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
                    readOne(pr, world.getEntityStore().getStore(), reader);
                } catch (RuntimeException | LinkageError ignored) {
                    // the player left while the task waited
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            // no engine
        }
    }

    private static void readOne(PlayerRef pr, Store<EntityStore> store, Reader reader) {
        if (pr == null) {
            return;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return;
        }
        try {
            reader.read(pr.getUuid(), store, ref);
        } catch (Throwable ignored) {
            // the reader guards itself
        }
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
