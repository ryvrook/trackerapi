package dev.ryvrook.trackerapi.mods.zephyr;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.facts.WorldTasks;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The real engine behind the Zephyr binding. Events go through TrackerAPI's own event registry, refs are read
 * through the store of the anchor ref on the world thread the mod fired from, and the component poll posts one
 * task per world through a {@link WorldTasks.Gate} like the sampler does. Every read that may meet a reshaped
 * engine class answers the world or skips the entity instead of throwing.
 */
final class LiveEngine implements Engine {
    private final BiConsumer<Class<?>, Consumer<Object>> bus;
    private final WorldTasks.Gate gate = new WorldTasks.Gate(10_000);

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

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void eachPlayer(Object componentType, BiConsumer<UUID, Object> visit) {
        if (!(componentType instanceof ComponentType<?, ?> type)) {
            return;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        gate.sweep();
        for (World world : universe.getWorlds().values()) {
            try {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                gate.post(world, () -> visitWorld(world, (ComponentType) type, visit));
            } catch (RuntimeException | LinkageError e) {
                // a world on its way out, the next one
            }
        }
    }

    /** World thread. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void visitWorld(World world, ComponentType type, BiConsumer<UUID, Object> visit) {
        Store<EntityStore> store;
        try {
            store = world.getEntityStore().getStore();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        for (PlayerRef pr : world.getPlayerRefs()) {
            try {
                Ref<EntityStore> ref = pr.getReference();
                if (ref == null || !ref.isValid() || ref.getStore() != store) {
                    continue; // moved to another world since the list was read
                }
                Object component = store.getComponent(ref, type);
                if (component != null) {
                    visit.accept(pr.getUuid(), component);
                }
            } catch (RuntimeException | LinkageError e) {
                // the next player
            }
        }
    }
}
