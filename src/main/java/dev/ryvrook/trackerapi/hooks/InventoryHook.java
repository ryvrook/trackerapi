package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MaterialTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.ResourceTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.TagTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ItemBroke;
import dev.ryvrook.trackerapi.api.event.ItemConsumed;
import dev.ryvrook.trackerapi.api.event.ItemDropped;
import dev.ryvrook.trackerapi.api.event.ItemEquipped;
import dev.ryvrook.trackerapi.api.event.ItemsGained;
import dev.ryvrook.trackerapi.api.event.ItemsLost;
import dev.ryvrook.trackerapi.facts.RecentContainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code InventoryChangeEvent} on players, the Feats InventorySystem transaction walk extended to every
 * item kind. World thread.
 *
 * <p>Gains and losses. Only the sections a player carries count (hotbar, storage, backpack, utility).
 * Loot, mob drops, ground pickups and crafted results arrive as add transactions. Taking items out of a
 * chest or bench arrives as a move into this section whose other side is not one of the player's own
 * sections, a gain with source CONTAINER. A move out to such a container is a loss, STASHED. Moves between
 * two of the player's own sections, sorting and swapping are skipped, so rearranging never counts. A top
 * level removal that names no stack is the drop key, a whole stack leaving such as death drops is the
 * same, both DROPPED, or DEATH when the player is dead. Items put away or dropped go into the stash and
 * dropped items into the recent drops, so taking them back is not fresh.
 *
 * <p>Meals and potions. A finished eat or drink takes exactly one Food_ or Potion_ item out of the hotbar
 * or the utility belt through a removal that names the held stack. A placement just before it makes it a
 * placement instead. Any other removal that names a stack from the hand is a loss, OTHER.
 *
 * <p>Equips and wear. A change in the Armor or Tool section publishes ITEM_EQUIPPED for every slot whose
 * item changed. A slot whose stack went from durability above zero to broken publishes ITEM_BROKE, in any
 * of those sections.
 *
 * <p>Creative mode is skipped for gains, losses, meals and drops, as Feats and Chronicle do, its items are
 * free. A change that moves nothing countable allocates nothing.
 */
final class InventoryHook extends AnyKindPlayerSystem<InventoryChangeEvent> {
    private static final String ARMOR = "Armor";
    private static final String TOOL = "Tool";

    private final ItemHooks h;
    /** The player's own sections, resolved once on first use since the engine registers them at start. */
    private volatile List<ComponentType<EntityStore, ? extends InventoryComponent>> sections;

    InventoryHook(ItemHooks hooks) {
        super(InventoryChangeEvent.class, hooks.s, EventKind.ITEMS_GAINED, EventKind.ITEMS_LOST,
                EventKind.ITEM_CONSUMED, EventKind.ITEM_DROPPED, EventKind.ITEM_EQUIPPED, EventKind.ITEM_BROKE,
                EventKind.ITEM_PICKED_UP);
        this.h = hooks;
    }

    @Override
    protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                InventoryChangeEvent event) {
        Object section = event.getComponentType();
        boolean hand = section == InventoryComponent.Hotbar.getComponentType()
                || section == InventoryComponent.Utility.getComponentType();
        boolean carried = hand
                || section == InventoryComponent.Storage.getComponentType()
                || section == InventoryComponent.Backpack.getComponentType();
        String equipSection = carried ? null
                : section == InventoryComponent.Armor.getComponentType() ? ARMOR
                : section == InventoryComponent.Tool.getComponentType() ? TOOL : null;
        if (!carried && equipSection == null) {
            return;
        }
        Transaction transaction = event.getTransaction();
        if (transaction == null || !transaction.succeeded()) {
            return;
        }
        String world = worldName(store);
        boolean broke = s.publisher.wants(EventKind.ITEM_BROKE);
        boolean equip = equipSection != null && s.publisher.wants(EventKind.ITEM_EQUIPPED);
        if (broke || equip) {
            scan(uuid, world, equipSection, transaction, broke, equip);
        }
        if (!carried || BlockHooks.creative(store, ref)) {
            return;
        }

        if (hand && transaction instanceof ItemStackSlotTransaction slot) {
            handRemoval(uuid, world, slot);
        }

        Totals t = walk(transaction, true, false, ref, store, null);
        if (t == null) {
            return;
        }
        long now = System.nanoTime();
        if (t.stashed != null) {
            RecentContainers.Use at = h.blocks.containers().recent(uuid, now);
            boolean lost = s.publisher.wants(EventKind.ITEMS_LOST);
            for (Map.Entry<String, Long> e : t.stashed.entrySet()) {
                h.stash.record(uuid, e.getKey(), e.getValue(), now);
                if (lost) {
                    s.publisher.publish(lost(world, uuid, e.getKey(), e.getValue(), ItemsLost.How.STASHED, at));
                }
            }
        }
        if (t.dropped != null) {
            boolean dead = isDead(store, ref);
            boolean lost = s.publisher.wants(EventKind.ITEMS_LOST);
            boolean drop = !h.dropEventFires && s.publisher.wants(EventKind.ITEM_DROPPED);
            for (Map.Entry<String, Long> e : t.dropped.entrySet()) {
                h.stash.record(uuid, e.getKey(), e.getValue(), now);
                h.drops.record(uuid, e.getKey(), e.getValue(), now);
                if (lost) {
                    s.publisher.publish(lost(world, uuid, e.getKey(), e.getValue(),
                            dead ? ItemsLost.How.DEATH : ItemsLost.How.DROPPED, null));
                }
                if (drop) {
                    s.publisher.publish(new ItemDropped(s.header(world, uuid), e.getKey(), e.getValue(), dead));
                }
            }
        }
        if (t.fromContainer != null) {
            RecentContainers.Use at = h.blocks.containers().recent(uuid, now);
            for (Map.Entry<String, Long> e : t.fromContainer.entrySet()) {
                gain(world, uuid, e.getKey(), e.getValue(), ItemsGained.Source.CONTAINER, at, now);
            }
        }
        if (t.gained != null) {
            for (Map.Entry<String, Long> e : t.gained.entrySet()) {
                gain(world, uuid, e.getKey(), e.getValue(), h.sourceOf(uuid, e.getKey(), now), null, now);
            }
        }
    }

    private void gain(String world, UUID uuid, String itemId, long amount, ItemsGained.Source source,
                      RecentContainers.Use at, long now) {
        long fresh = amount - h.stash.take(uuid, itemId, amount, now);
        if (!s.publisher.wants(EventKind.ITEMS_GAINED)) {
            return;
        }
        boolean atBlock = at != null;
        s.publisher.publish(new ItemsGained(s.header(world, uuid), itemId, amount, fresh, source, atBlock,
                atBlock ? at.x() : 0, atBlock ? at.y() : 0, atBlock ? at.z() : 0));
    }

    private ItemsLost lost(String world, UUID uuid, String itemId, long amount, ItemsLost.How how,
                           RecentContainers.Use at) {
        boolean atBlock = at != null;
        return new ItemsLost(s.header(world, uuid), itemId, amount, how, atBlock,
                atBlock ? at.x() : 0, atBlock ? at.y() : 0, atBlock ? at.z() : 0);
    }

    // ---- meals, potions and placements --------------------------------------------------------------

    /** A top level removal from the hand that names the held stack. */
    private void handRemoval(UUID uuid, String world, ItemStackSlotTransaction slot) {
        if (slot.getAction() != ActionType.REMOVE || slot.getQuery() == null) {
            return;
        }
        Counted gone = removed(slot);
        if (gone == null) {
            return;
        }
        ItemsLost.How how;
        if (h.blocks.placements().consume(uuid, gone.itemId())) {
            how = ItemsLost.How.PLACED;
        } else if (gone.amount() == 1 && consumable(gone.itemId())) {
            how = ItemsLost.How.CONSUMED;
            if (s.publisher.wants(EventKind.ITEM_CONSUMED)) {
                s.publisher.publish(new ItemConsumed(s.header(world, uuid), gone.itemId()));
            }
        } else {
            how = ItemsLost.How.OTHER;
        }
        if (s.publisher.wants(EventKind.ITEMS_LOST)) {
            s.publisher.publish(lost(world, uuid, gone.itemId(), gone.amount(), how, null));
        }
    }

    /** A meal or a potion, the Feats rule. Empty bottles are not potions. */
    static boolean consumable(String itemId) {
        return itemId != null && (itemId.startsWith("Food_")
                || (itemId.startsWith("Potion_") && !itemId.startsWith("Potion_Empty")));
    }

    private static boolean isDead(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            return store.getComponent(ref, DeathComponent.getComponentType()) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ---- equips and wear ------------------------------------------------------------------------------

    /**
     * Visits every slot this section changed. A move into this section is its add side, a move out its
     * remove side. Publishes equips when {@code section} is Armor or Tool and broken stacks anywhere.
     */
    private void scan(UUID uuid, String world, String section, Transaction transaction, boolean broke, boolean equip) {
        if (transaction == null || !transaction.succeeded()) {
            return;
        }
        if (transaction instanceof MoveTransaction<?> move) {
            if (move.getMoveType() == MoveType.MOVE_TO_SELF) {
                scan(uuid, world, section, move.getAddTransaction(), false, equip);
            } else {
                scan(uuid, world, section, move.getRemoveTransaction(), false, equip);
            }
            return;
        }
        if (transaction instanceof ListTransaction<?> list) {
            for (Transaction part : list.getList()) {
                scan(uuid, world, section, part, broke, equip);
            }
            return;
        }
        if (transaction instanceof ItemStackTransaction stack) {
            for (ItemStackSlotTransaction part : stack.getSlotTransactions()) {
                scan(uuid, world, section, part, broke, equip);
            }
            return;
        }
        if (transaction instanceof SlotTransaction slot) {
            ItemStack before = slot.getSlotBefore();
            ItemStack after = slot.getSlotAfter();
            String beforeId = isEmpty(before) ? null : before.getItemId();
            String afterId = isEmpty(after) ? null : after.getItemId();
            if (equip && !Objects.equals(beforeId, afterId)) {
                s.publisher.publish(new ItemEquipped(s.header(world, uuid), section, slot.getSlot(), afterId, beforeId));
            }
            if (broke && beforeId != null && beforeId.equals(afterId) && before.getDurability() > 0 && after.isBroken()) {
                s.publisher.publish(new ItemBroke(s.header(world, uuid), afterId));
            }
        }
    }

    // ---- the walk -----------------------------------------------------------------------------------

    /** What one change moved, item id to amount. Made on the first countable slot, each map on first use. */
    private static final class Totals {
        Map<String, Long> gained;
        Map<String, Long> fromContainer;
        Map<String, Long> stashed;
        Map<String, Long> dropped;
    }

    private Totals walk(Transaction transaction, boolean topLevel, boolean fromContainer, Ref<EntityStore> ref,
                        Store<EntityStore> store, Totals t) {
        if (transaction == null || !transaction.succeeded()) {
            return t;
        }
        if (transaction instanceof MoveTransaction<?> move) {
            if (ownedByPlayer(move.getOtherContainer(), ref, store)) {
                return t; // a rearrangement inside the player's own inventory
            }
            if (move.getMoveType() == MoveType.MOVE_TO_SELF) {
                return walk(move.getAddTransaction(), false, true, ref, store, t);
            }
            Counted gone = removed(move.getRemoveTransaction());
            if (gone != null) {
                t = totals(t);
                t.stashed = note(t.stashed, gone);
            }
            return t;
        }
        if (transaction instanceof MaterialTransaction || transaction instanceof ResourceTransaction
                || transaction instanceof TagTransaction) {
            return t; // crafting and repairs taking ingredients
        }
        if (transaction instanceof ListTransaction<?> list) {
            for (Transaction part : list.getList()) {
                t = walk(part, false, fromContainer, ref, store, t);
            }
            return t;
        }
        if (transaction instanceof ItemStackTransaction stack) {
            if (stack.getAction() != null && stack.getAction().isAdd()) {
                for (ItemStackSlotTransaction part : stack.getSlotTransactions()) {
                    t = addGain(t, added(part), fromContainer);
                }
            }
            return t;
        }
        if (transaction instanceof ItemStackSlotTransaction slot) {
            if (slot.getAction() != null && slot.getAction().isAdd()) {
                t = addGain(t, added(slot), fromContainer);
            } else if (topLevel && slot.getAction() == ActionType.REMOVE && slot.getQuery() == null) {
                t = drop(t, removed(slot)); // the drop key
            }
            return t;
        }
        if (transaction.getClass() == SlotTransaction.class) {
            SlotTransaction slot = (SlotTransaction) transaction;
            if (slot.getAction() != null && slot.getAction().isAdd()) {
                t = addGain(t, added(slot), fromContainer);
            } else if (topLevel && slot.getAction() == ActionType.REMOVE) {
                t = drop(t, removed(slot)); // a whole stack leaving, such as death drops
            }
        }
        return t;
    }

    private static Totals totals(Totals t) {
        return t != null ? t : new Totals();
    }

    private static Totals addGain(Totals t, Counted counted, boolean fromContainer) {
        if (counted == null) {
            return t;
        }
        t = totals(t);
        if (fromContainer) {
            t.fromContainer = note(t.fromContainer, counted);
        } else {
            t.gained = note(t.gained, counted);
        }
        return t;
    }

    private static Totals drop(Totals t, Counted counted) {
        if (counted == null) {
            return t;
        }
        t = totals(t);
        t.dropped = note(t.dropped, counted);
        return t;
    }

    /** True when the container is one of this player's own inventory sections or a view over them. */
    private boolean ownedByPlayer(ItemContainer other, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (other == null) {
            return true; // nothing to tell it apart by, so never count it
        }
        for (ComponentType<EntityStore, ? extends InventoryComponent> type : sections()) {
            InventoryComponent component = store.getComponent(ref, type);
            ItemContainer mine = component != null ? component.getInventory() : null;
            if (mine != null && (other == mine || other.containsContainer(mine))) {
                return true;
            }
        }
        return false;
    }

    private List<ComponentType<EntityStore, ? extends InventoryComponent>> sections() {
        List<ComponentType<EntityStore, ? extends InventoryComponent>> list = sections;
        if (list == null) {
            list = List.of(
                    InventoryComponent.Hotbar.getComponentType(), InventoryComponent.Storage.getComponentType(),
                    InventoryComponent.Backpack.getComponentType(), InventoryComponent.Utility.getComponentType(),
                    InventoryComponent.Armor.getComponentType(), InventoryComponent.Tool.getComponentType(),
                    InventoryComponent.AbilitySlots.getComponentType(), InventoryComponent.RuneBag.getComponentType());
            sections = list;
        }
        return list;
    }

    /** What one slot gained, which is the new stack less whatever of the same item was already there. */
    private static Counted added(SlotTransaction slot) {
        if (slot == null || !slot.succeeded()) {
            return null;
        }
        ItemStack after = slot.getSlotAfter();
        if (isEmpty(after)) {
            return null;
        }
        ItemStack before = slot.getSlotBefore();
        long amount = after.getQuantity();
        if (!isEmpty(before) && after.getItemId().equals(before.getItemId())) {
            amount -= before.getQuantity();
        }
        return amount > 0 ? new Counted(after.getItemId(), amount) : null;
    }

    /** What one slot lost, which is the old stack less whatever of the same item is still there. */
    private static Counted removed(SlotTransaction slot) {
        if (slot == null || !slot.succeeded()) {
            return null;
        }
        ItemStack before = slot.getSlotBefore();
        if (isEmpty(before)) {
            return null;
        }
        ItemStack after = slot.getSlotAfter();
        long amount = before.getQuantity();
        if (!isEmpty(after) && before.getItemId().equals(after.getItemId())) {
            amount -= after.getQuantity();
        }
        return amount > 0 ? new Counted(before.getItemId(), amount) : null;
    }

    /** Adds to a total, creating the map on first use. Returns the map to keep. */
    private static Map<String, Long> note(Map<String, Long> totals, Counted counted) {
        if (counted.itemId() == null) {
            return totals;
        }
        if (totals == null) {
            totals = new HashMap<>(4);
        }
        totals.merge(counted.itemId(), counted.amount(), Long::sum);
        return totals;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getItemId() == null;
    }

    private record Counted(String itemId, long amount) {
    }
}
