package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ChunkLoaded;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * CHUNK_LOADED. There is no load event, so a {@code RefSystem} over the {@code WorldChunk} component sees
 * every chunk entity entering a world's chunk store, and only {@code AddReason.LOAD} is published. The
 * chunk is read through its packed index, one getter instead of two. World thread.
 */
public final class ChunkLoadSystem extends RefSystem<ChunkStore> {
    private final Services s;

    public ChunkLoadSystem(Services services) {
        this.s = services;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return WorldChunk.getComponentType();
    }

    @Override
    public void onEntityAdded(Ref<ChunkStore> ref, AddReason reason, Store<ChunkStore> store,
                              CommandBuffer<ChunkStore> commandBuffer) {
        if (reason != AddReason.LOAD || !s.publisher.wants(EventKind.CHUNK_LOADED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            WorldChunk wc = store.getComponent(ref, WorldChunk.getComponentType());
            if (wc == null) {
                return;
            }
            long index = wc.getIndex();
            String world = WorldNames.name(WorldNames.ofChunks(store));
            s.publisher.publish(new ChunkLoaded(s.header(world, null), ChunkKeys.x(index), ChunkKeys.z(index)));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CHUNK_LOADED, System.nanoTime() - start);
        }
    }

    @Override
    public void onEntityRemove(Ref<ChunkStore> ref, RemoveReason reason, Store<ChunkStore> store,
                               CommandBuffer<ChunkStore> commandBuffer) {
        // unloads come from ChunkUnloadSystem, which sees cancellation
    }
}
