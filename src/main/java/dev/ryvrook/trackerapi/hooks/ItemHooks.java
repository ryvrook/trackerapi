package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.AbilityCastUtil;
import com.hypixel.hytale.server.core.modules.projectile.component.ImpactModifiers;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ActiveSlotChanged;
import dev.ryvrook.trackerapi.api.event.EntityUsed;
import dev.ryvrook.trackerapi.api.event.InteractionStarted;
import dev.ryvrook.trackerapi.api.event.ItemCrafted;
import dev.ryvrook.trackerapi.api.event.ItemDropped;
import dev.ryvrook.trackerapi.api.event.ItemPickedUp;
import dev.ryvrook.trackerapi.api.event.ItemsGained;
import dev.ryvrook.trackerapi.facts.RecentDrops;
import dev.ryvrook.trackerapi.facts.RecentStash;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The {@code items} hook set. Crafting, hand pickups, thrown items, the inventory walk (gains, losses,
 * meals and potions, drops, equips and broken items), the held slot, interaction chains and entity uses.
 * Reads the last placement and the last used block from the {@link BlockHooks}, so it installs after
 * them. Each system is its own class because the engine removes systems by class. Every system runs on
 * the world thread of the store it fired for.
 */
public final class ItemHooks implements HookSet {
    /** How long a hand pickup or a craft explains an inventory gain of the same item. */
    static final long SOURCE_WINDOW_NANOS = 2_000_000_000L;

    final Services s;
    final BlockHooks blocks;
    final RecentDrops drops = new RecentDrops();
    final RecentStash stash = new RecentStash();
    private final Map<UUID, SourceNote> sources = new ConcurrentHashMap<>();
    /**
     * True once {@code DropItemEvent.Drop} fired on a player this session. Until then the inventory walk
     * publishes the drop key drops itself, the Chronicle fallback, since the event's store and shape were
     * inferred and registration alone proves nothing.
     */
    volatile boolean dropEventFires;

    public ItemHooks(Services services, BlockHooks blocks) {
        this.s = services;
        this.blocks = blocks;
    }

    @Override
    public String name() {
        return "items";
    }

    public RecentDrops drops() {
        return drops;
    }

    public RecentStash stash() {
        return stash;
    }

    @Override
    public void install(PluginBase plugin, Services services, Registrar registrar) {
        drops.registerWith(services.forgetting);
        stash.registerWith(services.forgetting);
        services.forgetting.onPlayer(sources::remove);
        registrar.entitySystem("item crafted", () -> new CraftSystem(this));
        registrar.entitySystem("item craft start", () -> new CraftStartSystem(this));
        registrar.entitySystem("item picked up", () -> new PickupSystem(this));
        registrar.entitySystem("item dropped", () -> new DropSystem(this));
        registrar.entitySystem("inventory changes", () -> new InventoryHook(this));
        registrar.entitySystem("active slot changed", () -> new ActiveSlotSystem(this));
        registrar.entitySystem("interaction started", () -> new InteractionSystem(this));
        registrar.entitySystem("entity used", () -> new UseEntitySystem(this));
    }

    @Override
    public void stop() {
        sources.clear();
    }

    // ---- where a gain came from -------------------------------------------------------------------------

    /** The last hand pickup and the last craft output of one player. Written on the player's world thread. */
    private static final class SourceNote {
        volatile String pickupId;
        volatile long pickupAt;
        volatile String craftId;
        volatile long craftAt;
    }

    void notePickup(UUID uuid, String itemId) {
        if (uuid != null && itemId != null) {
            SourceNote note = sources.computeIfAbsent(uuid, id -> new SourceNote());
            note.pickupId = itemId;
            note.pickupAt = System.nanoTime();
        }
    }

    void noteCraft(UUID uuid, String itemId) {
        if (uuid != null && itemId != null) {
            SourceNote note = sources.computeIfAbsent(uuid, id -> new SourceNote());
            note.craftId = itemId;
            note.craftAt = System.nanoTime();
        }
    }

    /** PICKUP or CRAFT when the player just picked up or crafted this item, else OTHER. */
    ItemsGained.Source sourceOf(UUID uuid, String itemId, long now) {
        SourceNote note = sources.get(uuid);
        if (note == null || itemId == null) {
            return ItemsGained.Source.OTHER;
        }
        if (itemId.equals(note.pickupId) && now - note.pickupAt <= SOURCE_WINDOW_NANOS) {
            return ItemsGained.Source.PICKUP;
        }
        if (itemId.equals(note.craftId) && now - note.craftAt <= SOURCE_WINDOW_NANOS) {
            return ItemsGained.Source.CRAFT;
        }
        return ItemsGained.Source.OTHER;
    }

    static String outputOf(CraftingRecipe recipe) {
        if (recipe == null) {
            return null;
        }
        MaterialQuantity output = recipe.getPrimaryOutput();
        return output != null ? output.getItemId() : null;
    }

    // ---- ITEM_CRAFTED -----------------------------------------------------------------------------------

    /** {@code CraftRecipeEvent.Post} on players. Also notes the output for the gain that follows. */
    static final class CraftSystem extends AnyKindPlayerSystem<CraftRecipeEvent.Post> {
        private final ItemHooks h;

        CraftSystem(ItemHooks hooks) {
            super(CraftRecipeEvent.Post.class, hooks.s, EventKind.ITEM_CRAFTED, EventKind.ITEMS_GAINED);
            this.h = hooks;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    CraftRecipeEvent.Post event) {
            if (event.isCancelled()) {
                return;
            }
            CraftingRecipe recipe = event.getCraftedRecipe();
            if (recipe == null) {
                return;
            }
            String itemId = outputOf(recipe);
            h.noteCraft(uuid, itemId);
            if (!s.publisher.wants(EventKind.ITEM_CRAFTED)) {
                return;
            }
            s.publisher.publish(new ItemCrafted(s.header(worldName(store), uuid), recipe.getId(), itemId,
                    Math.max(1, event.getQuantity())));
        }
    }

    /**
     * {@code CraftRecipeEvent.Pre} on players, only to note the output, in case the engine adds the crafted
     * items to the inventory before it posts the Post event. Publishes nothing.
     */
    static final class CraftStartSystem extends PlayerEventSystem<CraftRecipeEvent.Pre> {
        private final ItemHooks h;

        CraftStartSystem(ItemHooks hooks) {
            super(CraftRecipeEvent.Pre.class, EventKind.ITEMS_GAINED, hooks.s);
            this.h = hooks;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    CraftRecipeEvent.Pre event) {
            if (!event.isCancelled()) {
                h.noteCraft(uuid, outputOf(event.getCraftedRecipe()));
            }
        }
    }

    // ---- ITEM_PICKED_UP ---------------------------------------------------------------------------------

    /** {@code InteractivelyPickupItemEvent} on players. Fresh is what the player had not just dropped. */
    static final class PickupSystem extends AnyKindPlayerSystem<InteractivelyPickupItemEvent> {
        private final ItemHooks h;

        PickupSystem(ItemHooks hooks) {
            super(InteractivelyPickupItemEvent.class, hooks.s, EventKind.ITEM_PICKED_UP, EventKind.ITEMS_GAINED);
            this.h = hooks;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    InteractivelyPickupItemEvent event) {
            if (event.isCancelled()) {
                return;
            }
            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty() || stack.getItemId() == null) {
                return;
            }
            String itemId = stack.getItemId();
            h.notePickup(uuid, itemId);
            if (!s.publisher.wants(EventKind.ITEM_PICKED_UP)) {
                return;
            }
            long amount = Math.max(1, stack.getQuantity());
            long fresh = amount - h.drops.take(uuid, itemId, amount);
            s.publisher.publish(new ItemPickedUp(s.header(worldName(store), uuid), itemId, amount, fresh));
        }
    }

    // ---- ITEM_DROPPED (engine drop) ---------------------------------------------------------------------

    /**
     * {@code DropItemEvent.Drop} on players, the Chronicle DropSystem. The first call turns the inventory
     * fallback off, whether or not anyone wants the kind, so the two paths never both publish one drop.
     */
    static final class DropSystem extends PlayerEventSystem<DropItemEvent.Drop> {
        private final ItemHooks h;

        DropSystem(ItemHooks hooks) {
            super(DropItemEvent.Drop.class, EventKind.ITEM_DROPPED, hooks.s);
            this.h = hooks;
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, DropItemEvent.Drop event) {
            if (!h.dropEventFires) {
                h.dropEventFires = true;
                s.logger.at(Level.INFO).log("TrackerAPI saw the first item drop on a player, the inventory drop fallback is now off");
            }
            super.handle(index, chunk, store, commandBuffer, event);
        }

        @Override
        public void onSystemUnregistered() {
            h.dropEventFires = false;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    DropItemEvent.Drop event) {
            if (event.isCancelled()) {
                return;
            }
            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty() || stack.getItemId() == null) {
                return;
            }
            s.publisher.publish(new ItemDropped(s.header(worldName(store), uuid), stack.getItemId(),
                    Math.max(1, stack.getQuantity()), false));
        }
    }

    // ---- ACTIVE_SLOT_CHANGED ----------------------------------------------------------------------------

    /** {@code InventorySetActiveSlotEvent} on players, hotbar section only. */
    static final class ActiveSlotSystem extends PlayerEventSystem<InventorySetActiveSlotEvent> {
        ActiveSlotSystem(ItemHooks hooks) {
            super(InventorySetActiveSlotEvent.class, EventKind.ACTIVE_SLOT_CHANGED, hooks.s);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    InventorySetActiveSlotEvent event) {
            if (event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID) {
                return;
            }
            int to = event.getNewSlot();
            if (to == event.getPreviousSlot()) {
                return;
            }
            String held = BlockHooks.itemId(InventoryComponent.getItemInHand(store, ref));
            s.publisher.publish(new ActiveSlotChanged(s.header(worldName(store), uuid), event.getPreviousSlot(), to, held));
        }
    }

    // ---- INTERACTION_STARTED ----------------------------------------------------------------------------

    /**
     * {@code InteractionChainStartEvent} on players, the Feats InteractionSystem. Only chains the player
     * starts directly, follow up chains such as projectile hits and held effects would count one action
     * twice. An ability root is affordable when the player can pay every cost, compared the way the
     * engine's StatsCondition does it.
     */
    static final class InteractionSystem extends PlayerEventSystem<InteractionChainStartEvent> {
        private static final String ABILITY_ROOT = "Root_Ability_";

        InteractionSystem(ItemHooks hooks) {
            super(InteractionChainStartEvent.class, EventKind.INTERACTION_STARTED, hooks.s);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    InteractionChainStartEvent event) {
            if (event.isCancelled()) {
                return;
            }
            InteractionType type = event.getType();
            if (!isPlayerAction(type)) {
                return;
            }
            String root = event.getRootInteractionId();
            if (root == null) {
                return;
            }
            boolean affordable = !root.startsWith(ABILITY_ROOT) || canAfford(event.getContext(), ref, store);
            s.publisher.publish(new InteractionStarted(s.header(worldName(store), uuid), root, type.name(), affordable));
        }

        /**
         * Mirrors the ability chain's opening StatsCondition. {@code AbilityCastUtil.resolveCostMap} returns
         * the cost per stat index, or null when the ability is free or the player is in Creative. The engine
         * applies cast modifiers from a snapshot taken on this same event, so when that snapshot is not there
         * yet the same modifiers are collected here.
         */
        private static boolean canAfford(InteractionContext context, Ref<EntityStore> ref, Store<EntityStore> store) {
            if (context == null) {
                return false;
            }
            Int2FloatMap costs = AbilityCastUtil.resolveCostMap(context, false);
            if (costs == null || costs.isEmpty()) {
                return true;
            }
            float multiplier = 1f;
            if (context.getMetaStore().getIfPresentMetaObject(ImpactModifiers.CAST_SNAPSHOT) == null) {
                ImpactModifiers modifiers = ImpactModifiers.collect(context, ref, store);
                if (modifiers != null) {
                    multiplier = modifiers.getAttribute(AbilityCastUtil.COST_ATTRIBUTE, 1f);
                }
            }
            EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
            if (stats == null) {
                return false;
            }
            for (Int2FloatMap.Entry cost : costs.int2FloatEntrySet()) {
                EntityStatValue value = stats.get(cost.getIntKey());
                if (value == null || value.get() < cost.getFloatValue() * multiplier) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isPlayerAction(InteractionType type) {
            if (type == null) {
                return false;
            }
            return switch (type) {
                case Primary, Secondary, Ability1, Ability2, Ability3, Ability4, Use -> true;
                default -> false;
            };
        }
    }

    // ---- ENTITY_USED ------------------------------------------------------------------------------------

    /** {@code UseEntityEvent.Post} on players. The target is a player, a creature or the world. */
    static final class UseEntitySystem extends PlayerEventSystem<UseEntityEvent.Post> {
        UseEntitySystem(ItemHooks hooks) {
            super(UseEntityEvent.Post.class, EventKind.ENTITY_USED, hooks.s);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    UseEntityEvent.Post event) {
            InteractionType type = event.getInteractionType();
            s.publisher.publish(new EntityUsed(s.header(worldName(store), uuid), actorOf(event.getTargetEntity(), store),
                    type != null ? type.name() : null));
        }

        private static Actor actorOf(Ref<EntityStore> target, Store<EntityStore> store) {
            if (!readable(store, target)) {
                return Actor.WORLD; // gone, or an entity of another store whose components are not ours to read
            }
            PlayerRef player = store.getComponent(target, PlayerRef.getComponentType());
            if (player != null) {
                return Actor.player(player.getUuid(), player.getUsername());
            }
            NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
            if (npc != null) {
                UUIDComponent id = store.getComponent(target, UUIDComponent.getComponentType());
                return Actor.creature(id != null ? id.getUuid() : null, npc.getNPCTypeId());
            }
            return Actor.WORLD;
        }
    }
}
