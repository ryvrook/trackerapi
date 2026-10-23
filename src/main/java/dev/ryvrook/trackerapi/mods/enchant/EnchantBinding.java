package dev.ryvrook.trackerapi.mods.enchant;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.EnchantmentTriggered;
import dev.ryvrook.trackerapi.api.event.ItemEnchanted;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The binding to Simple Enchantments 1.2.1 ({@code org.herolias:SimpleEnchantments}), written from its adapter
 * contract. The mod dispatches two plain {@code IEvent<Void>} classes on the engine bus and keeps no listener list
 * of its own, so the binding registers for both through {@link EngineBus} with the event classes loaded from the
 * mod's loader, and the registrations are undone at unbind. Nothing of the mod is named at compile time.
 *
 * <p>{@code ItemEnchantedEvent} fires once per successful scroll or command enchant and becomes
 * {@link ItemEnchanted} with the item id, the enchant id and the level. {@code EnchantmentActivatedEvent} fires per
 * hit, per block damage tick and per heal tick for some enchantments, so the callback passes a {@link ProcGate}
 * first, one {@link EnchantmentTriggered} per player and enchant id per second, and reads nothing else. Both
 * callbacks arrive on a world thread for the table paths and on an unknown thread for commands and a few engine
 * listener systems, so they copy the uuid, the item id and the enchant id and publish, no store reads.
 *
 * <p>The equipped enchantment read of the API ({@code equippedItemEnchantments(Player)}) walks the player's
 * inventory on the world thread and needs the entity, not a cheap getter, so it is left out. The enchanting table,
 * the engraving table and the scroll crafts are counted by the core as block uses and crafts.
 */
public final class EnchantBinding extends AbstractBinding {
    public static final String ID = "enchant";
    public static final String PLUGIN_ID = "org.herolias:SimpleEnchantments";
    public static final String NAME = "Simple Enchantments";

    public static final String F_ENCHANTED = "items enchanted";
    public static final String F_ACTIVATIONS = "activations";

    /**
     * The class names of the mod's API. Production uses {@link #PRODUCTION}, a test points them at a fake with the
     * same shape, or at a missing class to get a partial bind.
     */
    public record Names(String enchantedEvent, String activatedEvent, String enchantmentType) {
        /** The names under one root package, the events in {@code api.event} and the type in {@code enchantment}. */
        public static Names under(String root) {
            return new Names(root + ".api.event.ItemEnchantedEvent", root + ".api.event.EnchantmentActivatedEvent",
                    root + ".enchantment.EnchantmentType");
        }

        public static final Names PRODUCTION = under("org.herolias.plugin");
    }

    private final Names names;
    private final EngineBus bus;
    private final ProcGate gate;

    public EnchantBinding() {
        this(Names.PRODUCTION, EngineBus.ENGINE, new ProcGate(System::currentTimeMillis));
    }

    EnchantBinding(Names names, EngineBus bus, ProcGate gate) {
        super(ID, PLUGIN_ID, NAME);
        this.names = names;
        this.bus = bus;
        this.gate = gate;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.ITEM_ENCHANTED, EventKind.ENCHANTMENT_TRIGGERED);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(ModKeys.key(ID, ModKeys.ITEMS), "Items enchanted"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.ITEMS), "Enchantments applied", "Enchantment"),
                StatSpec.max(ModKeys.key(ID, ModKeys.HIGHEST_LEVEL), "Highest level applied"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.PROCS), "Enchantment activations"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.PROCS), "Activations by enchantment", "Enchantment"));
    }

    // ---- bind --------------------------------------------------------------------------------------------

    @Override
    protected void resolve(Object plugin) throws Throwable {
        Class<?> typeClass = Handles.type(loader, names.enchantmentType());
        MethodHandle enchantId = Handles.virtual(typeClass, "getId", String.class);
        onUnbind(gate::clear);

        feature(F_ENCHANTED, () -> {
            Class<?> event = Handles.type(loader, names.enchantedEvent());
            MethodHandle player = Handles.virtualByName(event, "getPlayerRef", 0);
            MethodHandle item = Handles.virtualByName(event, "getItem", 0);
            MethodHandle enchantment = Handles.virtual(event, "getEnchantment", typeClass);
            MethodHandle level = Handles.virtual(event, "getLevel", int.class);
            listen(event, e -> guard(F_ENCHANTED, () -> onEnchanted(e, player, item, enchantment, enchantId, level)));
        });
        feature(F_ACTIVATIONS, () -> {
            Class<?> event = Handles.type(loader, names.activatedEvent());
            MethodHandle player = Handles.virtualByName(event, "getPlayerRef", 0);
            MethodHandle item = Handles.virtualByName(event, "getItem", 0);
            MethodHandle enchantment = Handles.virtual(event, "getEnchantment", typeClass);
            listen(event, e -> guard(F_ACTIVATIONS, () -> onActivated(e, player, item, enchantment, enchantId)));
            Subscription left = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.PLAYER_LEFT), Delivery.ASYNC,
                    ev -> gate.forget(ev.header().player()));
            onUnbind(left::close);
        });
    }

    @Override
    protected void reset() {
        // keyed by the mod's stack classes, which would pin the old loader across a reload
        itemReaders.clear();
    }

    private void listen(Class<?> event, Consumer<Object> consumer) {
        Runnable undo = bus.listen(event, consumer);
        onUnbind(undo);
    }

    // ---- callbacks, the mod's thread ---------------------------------------------------------------------

    private void onEnchanted(Object event, MethodHandle player, MethodHandle item, MethodHandle enchantment,
                             MethodHandle enchantId, MethodHandle level) throws Throwable {
        if (!wants(EventKind.ITEM_ENCHANTED)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        if (uuid == null) {
            return;
        }
        String id = idOf(enchantment.invoke(event), enchantId);
        if (id == null) {
            return;
        }
        int lvl = Handles.intOf(level.invoke(event), 0);
        publish(new ItemEnchanted(header(uuid), ID, itemIdOf(item.invoke(event)), id, lvl));
    }

    private void onActivated(Object event, MethodHandle player, MethodHandle item, MethodHandle enchantment,
                             MethodHandle enchantId) throws Throwable {
        if (!wants(EventKind.ENCHANTMENT_TRIGGERED)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        if (uuid == null) {
            return;
        }
        String id = idOf(enchantment.invoke(event), enchantId);
        if (id == null || !gate.pass(uuid, id)) {
            return;
        }
        publish(new EnchantmentTriggered(header(uuid), ID, id, itemIdOf(item.invoke(event))));
    }

    private static String idOf(Object type, MethodHandle enchantId) throws Throwable {
        return type == null ? null : Handles.stringOf(enchantId.invoke(type));
    }

    /**
     * The item id of a stack, null for no stack or the empty one. An engine {@link ItemStack} is read as the item
     * hooks read it. Any other object with {@code getItemId()} and {@code isEmpty()} is read through handles found
     * once per class, so a stand in for the engine class in a test works the same way.
     */
    private String itemIdOf(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof ItemStack s) {
            return s.isEmpty() ? null : s.getItemId();
        }
        MethodHandle[] mh = itemReaders.computeIfAbsent(item.getClass(), EnchantBinding::itemReaders);
        if (mh.length == 0) {
            return null;
        }
        try {
            if (Handles.boolOf(mh[1].invoke(item), false)) {
                return null;
            }
            return Handles.stringOf(mh[0].invoke(item));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The id and empty readers of a stack class that is not the engine's, found once per class. */
    private final ConcurrentHashMap<Class<?>, MethodHandle[]> itemReaders = new ConcurrentHashMap<>();

    private static MethodHandle[] itemReaders(Class<?> type) {
        try {
            return new MethodHandle[]{Handles.virtualByName(type, "getItemId", 0), Handles.virtualByName(type, "isEmpty", 0)};
        } catch (ReflectiveOperationException e) {
            return new MethodHandle[0];
        }
    }
}
