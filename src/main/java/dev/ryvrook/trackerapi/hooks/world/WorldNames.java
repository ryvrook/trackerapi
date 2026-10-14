package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The world of a store and its name, read without throwing. World thread. */
final class WorldNames {
    private WorldNames() {
    }

    static World ofEntities(Store<EntityStore> store) {
        try {
            EntityStore data = store == null ? null : store.getExternalData();
            return data == null ? null : data.getWorld();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static World ofChunks(Store<ChunkStore> store) {
        try {
            ChunkStore data = store == null ? null : store.getExternalData();
            return data == null ? null : data.getWorld();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The name of a world, or null when it has none. */
    static String name(World world) {
        try {
            return world == null ? null : world.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
