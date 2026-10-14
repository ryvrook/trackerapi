package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * A {@link PlayerEventSystem} whose handler feeds several kinds, or keeps a fact other kinds read, so it
 * runs when any one of {@code kinds} is wanted instead of only the first. Times the body into the Perf
 * slot of the first kind. World thread.
 */
abstract class AnyKindPlayerSystem<E extends EcsEvent> extends PlayerEventSystem<E> {
    private final EventKind[] kinds;

    protected AnyKindPlayerSystem(Class<E> eventType, Services services, EventKind... kinds) {
        super(eventType, kinds[0], services);
        this.kinds = kinds;
    }

    /** True when at least one of the kinds this system feeds is wanted. */
    protected final boolean wantsAny() {
        for (EventKind k : kinds) {
            if (s.publisher.wants(k)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, E event) {
        if (!wantsAny()) {
            return;
        }
        long start = System.nanoTime();
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            handlePlayer(playerRef.getUuid(), playerRef, ref, store, event);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(kind, System.nanoTime() - start);
        }
    }
}
