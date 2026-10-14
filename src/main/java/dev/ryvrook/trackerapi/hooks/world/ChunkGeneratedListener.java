package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ChunkGenerated;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * CHUNK_GENERATED from {@code ChunkPreLoadProcessEvent} when {@code isNewlyGenerated()} (the Chronicle
 * {@code ChunkListener} read). The event is a processed event, so the listener reports itself through
 * {@code processEvent} once it is done, whatever it decided. Chunk loading path, possibly several threads at
 * once and possibly off the world thread, so nothing here touches components. Thread safe.
 */
public final class ChunkGeneratedListener {
    static final String PROCESSOR = "TrackerAPI";

    private final Services s;

    public ChunkGeneratedListener(Services services) {
        this.s = services;
    }

    public void register(EventRegistry events) {
        events.registerGlobal(ChunkPreLoadProcessEvent.class, this::onChunk);
    }

    private void onChunk(ChunkPreLoadProcessEvent event) {
        if (event == null) {
            return;
        }
        long start = System.nanoTime();
        try {
            if (!s.publisher.wants(EventKind.CHUNK_GENERATED) || !event.isNewlyGenerated()) {
                return;
            }
            WorldChunk wc = event.getChunk();
            if (wc == null && event.getHolder() != null) {
                wc = event.getHolder().getComponent(WorldChunk.getComponentType());
            }
            if (wc == null) {
                return;
            }
            String world = WorldNames.name(event.getWorld());
            s.publisher.publish(new ChunkGenerated(s.header(world, null), wc.getX(), wc.getZ()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            try {
                event.processEvent(PROCESSOR);
            } catch (RuntimeException | LinkageError ignored) {
                // the engine only uses it to time listeners
            }
            s.perf.record(EventKind.CHUNK_GENERATED, System.nanoTime() - start);
        }
    }
}
