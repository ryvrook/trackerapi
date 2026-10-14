package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.CreatureTamed;
import dev.ryvrook.trackerapi.facts.TameWatch;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.UUID;

/**
 * CREATURE_TAMED, the Feats TameSystem. In 0.7.1 an animal is tamed by its own role logic. A player uses
 * it with the right food in hand, the animal locks that player into its InteractionTarget slot, eats the
 * food and asks for a role change to its Tamed_ role. The role change system removes the entity with the
 * UNLOAD reason and adds it back under the new role with the same UUID, so this system sees both halves and
 * {@link TameWatch} pairs them. There is no taming event and the tamed animal keeps no owner.
 *
 * <p>Chunk loads and unloads also add and remove animals with these reasons, but they never come with a
 * pending role change, so they are ignored. World thread.
 */
public final class TameSystem extends RefSystem<EntityStore> {
    /** The marked entity slot the animal role locks the feeding player into. */
    static final String FEEDER_SLOT = "InteractionTarget";

    private final Services s;
    private final TameWatch watch;

    public TameSystem(Services services, TameWatch watch) {
        this.s = services;
        this.watch = watch;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
        if (reason != RemoveReason.UNLOAD || !s.publisher.wants(EventKind.CREATURE_TAMED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            Role role = npc != null ? npc.getRole() : null;
            if (role == null || !role.isRoleChangeRequested()) {
                return; // a chunk unload, not a role change
            }
            String oldRole = npc.getNPCTypeId();
            if (oldRole == null || oldRole.startsWith(TameWatch.TAMED_PREFIX)) {
                return;
            }
            MarkedEntitySupport marks = store.getComponent(ref, MarkedEntitySupport.getComponentType());
            Ref<EntityStore> feeder = marks != null ? marks.getMarkedEntityRef(FEEDER_SLOT) : null;
            if (!CombatReads.readable(store, feeder)) {
                return;
            }
            PlayerRef player = store.getComponent(feeder, PlayerRef.getComponentType());
            UUID animal = CombatReads.uuidOf(store, ref);
            if (player != null && animal != null) {
                watch.leaving(animal, oldRole, player.getUuid(), System.currentTimeMillis());
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CREATURE_TAMED, System.nanoTime() - start);
        }
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store,
                              CommandBuffer<EntityStore> commandBuffer) {
        if (reason != AddReason.LOAD || watch.idle()) {
            return; // nothing is waiting, which is nearly always
        }
        long start = System.nanoTime();
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            UUID animal = CombatReads.uuidOf(store, ref);
            if (npc == null || animal == null) {
                return;
            }
            String newRole = npc.getNPCTypeId();
            TameWatch.Tame tame = watch.arrive(animal, newRole, System.currentTimeMillis());
            if (tame != null && s.publisher.wants(EventKind.CREATURE_TAMED)) {
                s.publisher.publish(new CreatureTamed(s.header(PlayerEventSystem.worldName(store), tame.player()),
                        tame.fromRole(), animal, newRole));
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CREATURE_TAMED, System.nanoTime() - start);
        }
    }
}
