package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ProjectileLanded;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3d;

/**
 * PROJECTILE_LANDED. When a projectile strikes an entity or the ground the engine starts a
 * {@code ProjectileHit} or {@code ProjectileMiss} interaction chain built with
 * {@code InteractionContext.forProxyEntity(manager, shooter, projectile)}, so the context's owning entity
 * is the shooter, its running entity the projectile and its target entity what was struck. The chain runs
 * on the projectile's interaction manager, or the shooter's when the projectile has none, so the query is
 * every entity and everything is read from the context. Only projectiles whose config names a hit or miss
 * interaction start such a chain. World thread.
 */
public final class ProjectileLandSystem extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {
    private final Services s;

    public ProjectileLandSystem(Services services) {
        super(InteractionChainStartEvent.class);
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, InteractionChainStartEvent event) {
        if (!s.publisher.wants(EventKind.PROJECTILE_LANDED)) {
            return;
        }
        InteractionType type = event.getType();
        if (type != InteractionType.ProjectileHit && type != InteractionType.ProjectileMiss) {
            return;
        }
        long start = System.nanoTime();
        try {
            if (event.isCancelled()) {
                return;
            }
            InteractionContext context = event.getContext();
            if (context == null) {
                return;
            }
            Ref<EntityStore> projectile = context.getEntity();
            Actor shooter = CombatReads.actor(store, context.getOwningEntity());
            if (shooter.kind() == Actor.Kind.WORLD) {
                shooter = legacyShooter(store, projectile);
            }
            Actor hit = null;
            if (type == InteractionType.ProjectileHit) {
                Ref<EntityStore> target = context.getTargetEntity();
                hit = CombatReads.readable(store, target) ? CombatReads.actor(store, target) : null;
            }
            Vector3d pos = CombatReads.readable(store, projectile) ? CombatReads.position(store, projectile) : null;
            s.publisher.publish(new ProjectileLanded(s.header(PlayerEventSystem.worldName(store), shooter.playerUuid()),
                    shooter, projectileId(store, projectile), hit, pos != null ? pos.x : 0, pos != null ? pos.y : 0,
                    pos != null ? pos.z : 0));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PROJECTILE_LANDED, System.nanoTime() - start);
        }
    }

    /** The shooter of an older style projectile, from the creator uuid it carries, when it is a player online here. */
    private static Actor legacyShooter(Store<EntityStore> store, Ref<EntityStore> projectile) {
        try {
            if (!CombatReads.readable(store, projectile)) {
                return Actor.WORLD;
            }
            ProjectileComponent legacy = store.getComponent(projectile, ProjectileComponent.getComponentType());
            if (legacy == null || legacy.getCreatorUuid() == null) {
                return Actor.WORLD;
            }
            Ref<EntityStore> creator = store.getExternalData().getRefFromUUID(legacy.getCreatorUuid());
            return CombatReads.actor(store, creator);
        } catch (RuntimeException | LinkageError e) {
            return Actor.WORLD;
        }
    }

    /**
     * An id for a projectile entity. The older projectile component names its asset, a creature thrown as a
     * projectile has its NPC type id, and the current projectile carries only a marker, so its model asset id
     * stands in. Null when none can be read, which is usual at launch when the entity is still queued.
     */
    static String projectileId(Store<EntityStore> store, Ref<EntityStore> projectile) {
        try {
            if (!CombatReads.readable(store, projectile)) {
                return null;
            }
            ProjectileComponent legacy = store.getComponent(projectile, ProjectileComponent.getComponentType());
            if (legacy != null && legacy.getProjectileAssetName() != null) {
                return legacy.getProjectileAssetName();
            }
            NPCEntity npc = store.getComponent(projectile, NPCEntity.getComponentType());
            if (npc != null) {
                return npc.getNPCTypeId();
            }
            ModelComponent model = store.getComponent(projectile, ModelComponent.getComponentType());
            Model m = model != null ? model.getModel() : null;
            return m != null ? m.getModelAssetId() : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
