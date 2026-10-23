package dev.ryvrook.trackerapi.mods.arcanum;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * TrackerAPI's listener for Arcanum's cast outcome event, an ECS entity event the mod invokes from its cast tick
 * and its cast interaction, both on the world thread. One class for every bind, constructed with the event class
 * of the mod's current loader. The query names the engine's {@code PlayerRef} only, never a type of the mod, so
 * the engine can still validate and remove the system after the mod shut down. The prepared cast component is
 * read with the type resolved at bind, which is still on the player while the outcome is handled because the mod
 * queues its removal on the command buffer. Read only, nothing is added or removed here.
 */
final class ArcanumCastSystem extends EntityEventSystem<EntityStore, EcsEvent> {
    private final ComponentType<EntityStore, ?> preparedType;
    private volatile ArcanumEngine.CastListener listener;

    @SuppressWarnings("unchecked")
    ArcanumCastSystem(Class<?> eventClass, Object preparedType, ArcanumEngine.CastListener listener) {
        super((Class<EcsEvent>) eventClass.asSubclass(EcsEvent.class));
        this.preparedType = preparedType instanceof ComponentType<?, ?> t ? (ComponentType<EntityStore, ?>) t : null;
        this.listener = listener;
    }

    /** Stops handing events on, before the system is removed from the registry. */
    void close() {
        listener = null;
    }

    boolean isOpen() {
        return listener != null;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, EcsEvent event) {
        ArcanumEngine.CastListener l = listener;
        if (l == null) {
            return;
        }
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) {
                return;
            }
            Object prepared = null;
            if (preparedType != null) {
                try {
                    prepared = store.getComponent(ref, (ComponentType) preparedType);
                } catch (RuntimeException | LinkageError e) {
                    prepared = null; // the type went with the mod
                }
            }
            l.onCast(event, player.getUuid(), prepared, (section, slot) -> itemAt(ref, store, section, slot));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private static String itemAt(Ref<EntityStore> ref, Store<EntityStore> store, int section, int slot) {
        try {
            ItemContainer container = InventoryUtils.getSectionById(ref, section, store);
            if (container == null || slot < 0 || slot >= container.getCapacity()) {
                return null;
            }
            ItemStack stack = container.getItemStack((short) slot);
            return stack == null || stack.isEmpty() ? null : stack.getItemId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
