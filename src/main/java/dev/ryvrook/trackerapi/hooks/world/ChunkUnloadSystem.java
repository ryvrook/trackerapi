package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ChunkUnloaded;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * CHUNK_UNLOADED from {@code ChunkUnloadEvent} on the chunk store, the same shape as the chunk save. The
 * engine's unloading system also runs while a world is paused. Cancelled unloads are skipped. World thread.
 */
public final class ChunkUnloadSystem extends EntityEventSystem<ChunkStore, ChunkUnloadEvent> {
    private final Services s;

    public ChunkUnloadSystem(Services services) {
        super(ChunkUnloadEvent.class);
        this.s = services;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
                       CommandBuffer<ChunkStore> commandBuffer, ChunkUnloadEvent event) {
        if (!s.publisher.wants(EventKind.CHUNK_UNLOADED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            if (event.isCancelled()) {
                return;
            }
            WorldChunk wc = event.getChunk();
            if (wc == null) {
                return;
            }
            String world = WorldNames.name(WorldNames.ofChunks(store));
            s.publisher.publish(new ChunkUnloaded(s.header(world, null), wc.getX(), wc.getZ()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CHUNK_UNLOADED, System.nanoTime() - start);
        }
    }
}
