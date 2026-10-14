package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.facts.BossLedger;
import dev.ryvrook.trackerapi.facts.Bosses;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ENTITY_DIED, once per death of any entity, the Feats and Chronicle death systems in one. Extends
 * {@code DeathSystems.OnDeathSystem} with query {@code Archetype.empty()}, so it fires once when a
 * {@code DeathComponent} is added.
 *
 * <p>A killer ref is read only when it is valid, belongs to this store and is not the victim. A boss death
 * closes its fight in the {@link BossLedger}. The participants are the killer first, when a player, then
 * every player the ledger saw hit the boss in the last minute, the Feats credit rule. {@code untouched}
 * holds those of them the ledger never saw take damage during the fight. Each optional fact is read in its
 * own guard so one unreadable fact never loses the death. World thread.
 */
public final class EntityDiedSystem extends DeathSystems.OnDeathSystem {
    private final Services s;
    private final BossLedger ledger;

    public EntityDiedSystem(Services services, BossLedger ledger) {
        this.s = services;
        this.ledger = ledger;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        if (!s.publisher.wants(EventKind.ENTITY_DIED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            track(ref, death, store);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.ENTITY_DIED, System.nanoTime() - start);
        }
    }

    private void track(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store) {
        Damage info = death.getDeathInfo();
        Ref<EntityStore> killerRef = CombatReads.attacker(store, info, ref);
        boolean ranged = killerRef != null && info.getSource() instanceof Damage.ProjectileSource;
        boolean viaEffect = killerRef != null && info.getSource() instanceof Damage.EntityEffectSource;

        Actor victim = CombatReads.actor(store, ref);
        Actor killer = killerRef != null ? CombatReads.actor(store, killerRef) : Actor.WORLD;
        UUID victimPlayer = victim.playerUuid();
        UUID killerPlayer = killer.playerUuid();
        boolean boss = victim.isCreature() && Bosses.isBoss(victim.typeId());

        List<UUID> participants = List.of();
        Set<UUID> untouched = Set.of();
        if (boss) {
            Set<UUID> credited = new LinkedHashSet<>();
            if (killerPlayer != null) {
                credited.add(killerPlayer);
            }
            BossLedger.Outcome fight = BossLedger.Outcome.NONE;
            if (victim.uuid() != null && store.isInThread()) {
                fight = ledger.finish(victim.uuid(), System.nanoTime());
                credited.addAll(fight.credited());
            }
            if (!credited.isEmpty()) {
                participants = List.copyOf(new ArrayList<>(credited));
                Set<UUID> clean = new HashSet<>();
                for (UUID player : credited) {
                    if (fight.untouched(player)) {
                        clean.add(player);
                    }
                }
                untouched = Set.copyOf(clean);
            }
        }

        String cause = null;
        try {
            DamageCause damageCause = death.getDeathCause();
            cause = damageCause != null ? damageCause.getId() : null;
        } catch (RuntimeException | LinkageError e) {
            // the death still counts without its cause
        }
        boolean killerArmourless = killerPlayer != null && CombatReads.wearsNoArmour(store, killerRef);
        Set<UUID> armourless = boss && !participants.isEmpty() ? armourless(store, participants, killerPlayer, killerArmourless)
                : Set.of();
        Vector3d pos = CombatReads.position(store, ref);
        String world = PlayerEventSystem.worldName(store);
        UUID about = victimPlayer != null ? victimPlayer : killerPlayer;
        s.publisher.publish(new EntityDied(s.header(world, about), victim, killer, cause, CombatReads.effectId(info),
                ranged, viaEffect, pos != null ? pos.x : 0, pos != null ? pos.y : 0, pos != null ? pos.z : 0, boss,
                participants, untouched, killerArmourless, CombatReads.portalTypeId(store), itemsLost(death), armourless));
    }

    /**
     * The credited players still in this world who wear no armour, the Feats creditGobliterator read. Boss
     * kills are rare, so the walk over the world's players costs nothing worth measuring.
     */
    private static Set<UUID> armourless(Store<EntityStore> store, List<UUID> participants, UUID killer, boolean killerArmourless) {
        Set<UUID> out = new HashSet<>();
        if (killer != null && killerArmourless) {
            out.add(killer);
        }
        try {
            com.hypixel.hytale.server.core.universe.world.World world = PlayerEventSystem.worldOf(store);
            if (world == null || !store.isInThread()) {
                return Set.copyOf(out);
            }
            for (com.hypixel.hytale.server.core.universe.PlayerRef playerRef : world.getPlayerRefs()) {
                UUID player = playerRef.getUuid();
                if (player == null || player.equals(killer) || !participants.contains(player)) {
                    continue;
                }
                if (CombatReads.wearsNoArmour(store, playerRef.getReference())) {
                    out.add(player);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
        return Set.copyOf(out);
    }

    /**
     * Items the death cost, as Chronicle counts them, the sum of the stack quantities in
     * {@code getItemsLostOnDeath()}. Zero when unreadable.
     */
    private static int itemsLost(DeathComponent death) {
        try {
            ItemStack[] lost = death.getItemsLostOnDeath();
            if (lost == null) {
                return 0;
            }
            long sum = 0;
            for (ItemStack stack : lost) {
                if (stack != null) {
                    sum += Math.max(0, stack.getQuantity());
                }
            }
            return (int) Math.min(Integer.MAX_VALUE, sum);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }
}
