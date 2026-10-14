package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkSaveEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ChunkSaved;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * CHUNK_SAVED. The engine's chunk saving systems invoke {@code ChunkSaveEvent} on the chunk entity of the
 * chunk store, so this is registered on the chunk store registry (the Atlas {@code ChunkSaveSystem} read).
 * Cancelled saves are skipped. World thread.
 */
public final class ChunkSaveSystem extends EntityEventSystem<ChunkStore, ChunkSaveEvent> {
    private final Services s;

    public ChunkSaveSystem(Services services) {
        super(ChunkSaveEvent.class);
        this.s = services;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
                       CommandBuffer<ChunkStore> commandBuffer, ChunkSaveEvent event) {
        if (!s.publisher.wants(EventKind.CHUNK_SAVED)) {
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
            s.publisher.publish(new ChunkSaved(s.header(world, null), wc.getX(), wc.getZ()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CHUNK_SAVED, System.nanoTime() - start);
        }
    }
}
