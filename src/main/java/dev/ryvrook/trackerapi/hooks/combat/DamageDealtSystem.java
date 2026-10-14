package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.DamageDealt;
import dev.ryvrook.trackerapi.facts.BossLedger;
import dev.ryvrook.trackerapi.facts.Bosses;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3d;

import java.util.Set;
import java.util.UUID;

/**
 * DAMAGE_DEALT for every hit on every entity, the Feats and Chronicle damage systems in one. Query
 * {@code Archetype.empty()}, ordered after the engine's damage filter group when the engine allows it, so
 * the amount is the one left after armour and filters. A cancelled hit or one that rounds to nothing is
 * skipped, as both mods do.
 *
 * <p>Also feeds the {@link BossLedger}. A player hitting a boss is recorded, a player taking damage is
 * marked hurt, exactly as Feats does, so ENTITY_DIED can name every participant and who came through
 * untouched. The ledger is fed whenever someone wants either kind. World thread.
 */
public final class DamageDealtSystem extends EntityEventSystem<EntityStore, Damage> {
    private final Services s;
    private final BossLedger ledger;
    private final Set<Dependency<EntityStore>> dependencies;

    /**
     * @param ordered true to run after the damage filter group. Reading the group may throw on a reshaped
     *                engine, the caller then builds an unordered system instead.
     */
    public DamageDealtSystem(Services services, BossLedger ledger, boolean ordered) {
        super(Damage.class);
        this.s = services;
        this.ledger = ledger;
        this.dependencies = ordered ? afterFilters() : Set.of();
    }

    private static Set<Dependency<EntityStore>> afterFilters() {
        SystemGroup<EntityStore> filters = DamageModule.get().getFilterDamageGroup();
        if (filters == null) {
            throw new IllegalStateException("the damage filter group is not set up");
        }
        return Set.of(new SystemGroupDependency<>(Order.AFTER, filters));
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, Damage damage) {
        boolean publish = s.publisher.wants(EventKind.DAMAGE_DEALT);
        if (!publish && !s.publisher.wants(EventKind.ENTITY_DIED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            track(index, chunk, store, damage, publish);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.DAMAGE_DEALT, System.nanoTime() - start);
        }
    }

    private void track(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, Damage damage,
                       boolean publish) {
        if (damage.isCancelled()) {
            return;
        }
        float amount = damage.getAmount();
        if (Math.round(amount) <= 0) {
            return;
        }
        Ref<EntityStore> victim = chunk.getReferenceTo(index);
        Ref<EntityStore> attacker = CombatReads.attacker(store, damage, victim);
        Actor victimActor = CombatReads.actor(store, victim);
        Actor attackerActor = attacker != null ? CombatReads.actor(store, attacker) : Actor.WORLD;
        String world = PlayerEventSystem.worldName(store);
        UUID victimPlayer = victimActor.playerUuid();
        UUID attackerPlayer = attackerActor.playerUuid();
        boolean bossVictim = victimActor.isCreature() && Bosses.isBoss(victimActor.typeId());

        if (victimPlayer != null) {
            ledger.hurt(victimPlayer, world, System.nanoTime());
        }
        if (attackerPlayer != null && bossVictim && victimActor.uuid() != null && store.isInThread()) {
            ledger.record(victimActor.uuid(), attackerPlayer, world, System.nanoTime());
        }
        if (!publish) {
            return;
        }
        Vector3d pos = CombatReads.position(store, victim);
        UUID about = attackerPlayer != null ? attackerPlayer : victimPlayer;
        s.publisher.publish(new DamageDealt(s.header(world, about), attackerActor, victimActor, amount,
                CombatReads.causeId(damage), damage.getSource() instanceof Damage.ProjectileSource,
                CombatReads.effectId(damage), pos != null ? pos.x : 0, pos != null ? pos.y : 0, pos != null ? pos.z : 0,
                bossVictim));
    }
}
