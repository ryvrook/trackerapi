package dev.ryvrook.trackerapi.mods.parries;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.api.Actor;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The real engine behind the Perfect Parries binding. Events go through TrackerAPI's own event registry, refs are
 * read through the store of the anchor ref on the world thread the mod fired from, the way the combat hooks read
 * a damage source. Every read that may meet a reshaped engine class answers the world instead of throwing.
 */
final class LiveEngine implements Engine {
    private final BiConsumer<Class<?>, Consumer<Object>> bus;

    LiveEngine(BiConsumer<Class<?>, Consumer<Object>> bus) {
        this.bus = bus;
    }

    @Override
    public Runnable listen(Class<?> eventClass, Consumer<Object> consumer) {
        bus.accept(eventClass, consumer);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Actor actor(Object ref, Object anchor) {
        if (!(ref instanceof Ref<?> raw) || !(anchor instanceof Ref<?> rawAnchor)) {
            return Actor.WORLD;
        }
        try {
            Ref<EntityStore> entity = (Ref<EntityStore>) raw;
            Store<EntityStore> store = ((Ref<EntityStore>) rawAnchor).getStore();
            if (store == null || !entity.isValid() || entity.getStore() != store) {
                return Actor.WORLD;
            }
            PlayerRef player = store.getComponent(entity, PlayerRef.getComponentType());
            if (player != null) {
                return Actor.player(player.getUuid(), player.getUsername());
            }
            NPCEntity npc = store.getComponent(entity, NPCEntity.getComponentType());
            if (npc != null) {
                return Actor.creature(null, npc.getNPCTypeId());
            }
            return Actor.WORLD;
        } catch (RuntimeException | LinkageError e) {
            return Actor.WORLD;
        }
    }
}
