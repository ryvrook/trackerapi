package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.api.Actor;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * The engine reads the combat hooks share, ported from the Feats and Chronicle damage and death systems.
 * Every read that may meet a reshaped engine class catches its own failure and answers null, so one lost
 * fact never loses the event. World thread only.
 */
final class CombatReads {
    private CombatReads() {
    }

    /**
     * True when the ref can be read from this store. An attacker in another world (an arrow still flying
     * after its shooter took a portal) has a ref from another store, and reading it here would throw.
     */
    static boolean readable(Store<EntityStore> store, Ref<EntityStore> ref) {
        return ref != null && ref.isValid() && ref.getStore() == store;
    }

    /** The attacker ref of a damage source, when it is a readable entity other than the victim, else null. */
    static Ref<EntityStore> attacker(Store<EntityStore> store, Damage damage, Ref<EntityStore> victim) {
        if (damage != null && damage.getSource() instanceof Damage.EntitySource source) {
            Ref<EntityStore> attacker = source.getRef();
            if (readable(store, attacker) && !attacker.equals(victim)) {
                return attacker;
            }
        }
        return null;
    }

    /** A player, a creature with its NPC type id and entity uuid, or the world for anything else. */
    static Actor actor(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (!readable(store, ref)) {
            return Actor.WORLD;
        }
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            return Actor.player(player.getUuid(), player.getUsername());
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null) {
            return Actor.creature(uuidOf(store, ref), npc.getNPCTypeId());
        }
        return Actor.WORLD;
    }

    /** The entity's UUID component, or null. */
    static UUID uuidOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            return id != null ? id.getUuid() : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The entity's position, or null when it has no transform. */
    static Vector3d position(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            return t != null ? t.getPosition() : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The cause id, from the cause index through the DamageCause asset map, else from {@code getCause()},
     * which the engine marks deprecated in favour of the index. Null when unknown. The Chronicle read.
     */
    @SuppressWarnings("deprecation")
    static String causeId(Damage damage) {
        DamageCause cause;
        try {
            cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        } catch (RuntimeException | LinkageError e) {
            cause = null;
        }
        if (cause == null) {
            try {
                cause = damage.getCause();
            } catch (RuntimeException | LinkageError e) {
                cause = null;
            }
        }
        return cause != null ? cause.getId() : null;
    }

    /**
     * The id of the status effect whose damage this is, such as Lava_Burn, or null when it came from
     * anything else. Effect damage carries the effect as its source, wrapped in an entity source when the
     * effect has a living owner. The Feats read.
     */
    static String effectId(Damage info) {
        if (info == null) {
            return null;
        }
        try {
            Damage.Source source = info.getSource();
            ActiveEntityEffect effect = source instanceof Damage.EntityEffectSource owned ? owned.getEffect()
                    : source instanceof ActiveEntityEffect bare ? bare : null;
            if (effect == null) {
                return null;
            }
            EntityEffect asset = EntityEffect.getAssetMap().getAsset(effect.getEntityEffectIndex());
            return asset != null ? asset.getId() : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The portal type id of this world, the Feats isDeepworks read. Null for a world that is not a portal. */
    static String portalTypeId(Store<EntityStore> store) {
        try {
            PortalWorld portal = store.getResource(PortalWorld.getResourceType());
            if (portal == null || !portal.exists()) {
                return null;
            }
            PortalType type = portal.getPortalType();
            return type != null ? type.getId() : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The Feats wearsNoArmour. A ref from another store or an unreadable armour container counts as armoured. */
    static boolean wearsNoArmour(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            if (!readable(store, ref)) {
                return false;
            }
            InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            ItemContainer slots = armor != null ? armor.getInventory() : null;
            return slots != null && slots.isEmpty();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
