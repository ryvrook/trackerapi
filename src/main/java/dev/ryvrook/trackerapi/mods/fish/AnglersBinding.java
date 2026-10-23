package dev.ryvrook.trackerapi.mods.fish;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The binding to Angler's Almanac 1.2.2 ({@code dev.rm20:AnglersAlmanac}), written from its adapter contract. The
 * mod dispatches its events as {@code IEvent<Void>} on the engine bus, so the binding registers for them through
 * {@link FishEngine} with the event classes loaded from the mod's loader, and the registrations are undone at unbind.
 * Nothing of the mod is named at compile time.
 *
 * <p>{@code LootCaughtEvent} is the catch. It fires on a common pool thread after the mod's SQLite write, never on a
 * world thread, so the callback reads only the event, its loot and the player's {@code PlayerRef} getter, and
 * publishes. It never touches a store. A catch becomes {@link ModTally} records for the species or junk item, the
 * rarity, the grade and the bests, {@link ModAction} for legendary and golden fish, and {@link ModMilestone} for a
 * new species, a new Almanac page and a completed Almanac zone. The page list is read once per bind from the mod's
 * merged Almanac layout, on the first catch, which is an asset read and no database read.
 *
 * <p>The escape, the cast and the entity hook arrive on world threads. The cast and the hook are cancellable, they
 * are heard at the last priority and counted only when not cancelled, and the player of their entity refs is read
 * on that world thread.
 *
 * <p>Left out: the SQLite backfill of catches made before TrackerAPI, which would read the mod's database, and
 * {@code PreFishRollEvent}, which fires on every roll before the catch and carries only mutable context.
 */
public final class AnglersBinding extends AbstractBinding {
    public static final String ID = "fish";
    public static final String PLUGIN_ID = "dev.rm20:AnglersAlmanac";
    public static final String NAME = "Angler's Almanac";

    public static final String F_CATCH = "catch";
    public static final String F_PAGES = "pages";
    public static final String F_SIZE = "size";
    public static final String F_FAILED = "failed";
    public static final String F_CAST = "cast";
    public static final String F_HOOK = "hook";

    /** Tally stats. */
    static final String CAUGHT = "caught";
    static final String JUNK = "junk";
    static final String RARITY = "rarity";
    static final String GRADE = "grade";
    static final String PERFORMANCE = "performance";
    static final String PERFECT_STREAK = "perfect_streak";
    static final String SIZE = "size";
    /** Actions. */
    static final String LEGENDARY = "legendary";
    static final String GOLDEN = "golden";
    static final String ESCAPED = "escaped";
    static final String CAST = "cast";
    static final String HOOKED = "hooked";
    /** Milestone categories, singular so the totals are their plurals. */
    static final String CAT_SPECIES = "species";
    static final String CAT_PAGE = "page";
    static final String CAT_ZONE = "zone";

    /**
     * The class names of the mod's API. Production uses {@link #PRODUCTION}, a test points them at a fake with the
     * same shape, or at a missing class to get a partial bind.
     */
    public record Names(String caughtEvent, String failedEvent, String castEvent, String pullEvent, String fishLoot,
                        String lootManager, String book, String habitat, String fishEntry) {
        /** The names under one root package, the events in {@code IEvents} and the assets in {@code Models}. */
        public static Names under(String root) {
            String models = root + ".Models.";
            return new Names(root + ".IEvents.LootCaughtEvent", root + ".IEvents.FishingFailedEvent",
                    root + ".IEvents.FishingRodCastEvent", root + ".IEvents.FishingRodEntityPullEvent",
                    models + "FishLoot", models + "FishLootManager", models + "BookAssetData",
                    models + "BookAssetData$habitatsInfo", models + "BookAssetData$FishEntry");
        }

        public static final Names PRODUCTION = under("dev.rm20.anglersalmanac");
    }

    /** The Almanac layout of one bind, zone name to the loot ids with a page in it. */
    record Layout(Map<String, List<String>> zones) {
        List<String> zonesOf(String lootId) {
            List<String> out = new ArrayList<>(1);
            for (Map.Entry<String, List<String>> e : zones.entrySet()) {
                if (e.getValue().contains(lootId)) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
    }

    private final Names names;
    private final FishEngine engine;
    /** The current run of perfect catches per player, reset by any other grade, an escape or leaving. */
    private final ConcurrentHashMap<UUID, Integer> streaks = new ConcurrentHashMap<>();
    /** The page layout, read on the first catch of a bind. */
    private final AtomicReference<Layout> layout = new AtomicReference<>();
    /** The {@code getPlayerRef()} reader per player class, the engine's {@code Player} in production. */
    private final ConcurrentHashMap<Class<?>, MethodHandle> playerRefReaders = new ConcurrentHashMap<>();

    public AnglersBinding() {
        this(Names.PRODUCTION, FishEngine.ENGINE);
    }

    AnglersBinding(Names names, FishEngine engine) {
        super(ID, PLUGIN_ID, NAME);
        this.names = names;
        this.engine = engine;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_TALLY, EventKind.MOD_ACTION, EventKind.MOD_MILESTONE);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(key(CAUGHT), "Fish caught"),
                StatSpec.perId(key(CAUGHT), "Fish caught by species", "Species"),
                StatSpec.sum(key(JUNK), "Junk caught"),
                StatSpec.perId(key(JUNK), "Junk caught by item", "Item"),
                StatSpec.sum(key(RARITY), "All catches"),
                StatSpec.perId(key(RARITY), "Catches by rarity", "Rarity"),
                StatSpec.sum(key(GRADE), "Graded catches").noBoard(),
                StatSpec.perId(key(GRADE), "Catches by grade", "Grade"),
                StatSpec.max(key(GRADE + "_best"), "Best grade"),
                StatSpec.max(key(PERFORMANCE + "_best"), "Best performance"),
                StatSpec.max(key(PERFECT_STREAK + "_best"), "Longest perfect streak"),
                StatSpec.max(key(SIZE + "_best"), "Biggest species landed"),
                StatSpec.sum(key(LEGENDARY), "Legendary catches"),
                StatSpec.sum(key(GOLDEN), "Golden fish caught"),
                StatSpec.count(key(ModKeys.plural(CAT_SPECIES)), "Species discovered"),
                StatSpec.discovery(key(CAT_SPECIES), "Species by id", "Species"),
                StatSpec.count(key(ModKeys.plural(CAT_PAGE)), "Almanac pages filled"),
                StatSpec.discovery(key(CAT_PAGE), "Almanac pages by fish", "Fish"),
                StatSpec.count(key(ModKeys.plural(CAT_ZONE)), "Almanac zones completed"),
                StatSpec.discovery(key(CAT_ZONE), "Almanac zones by zone", "Zone"),
                StatSpec.sum(key(ESCAPED), "Fish escaped"),
                StatSpec.sum(key(CAST), "Casts"),
                StatSpec.sum(key(HOOKED), "Entities hooked"),
                StatSpec.sum(ModKeys.key(ID, HOOKED, ModKeys.PVP), "Players hooked"));
    }

    private static String key(String stat) {
        return ModKeys.key(ID, stat);
    }

    // ---- bind --------------------------------------------------------------------------------------------

    @Override
    protected void resolve(Object plugin) throws Throwable {
        // core: the loot getters every catch and escape reads
        Class<?> loot = Handles.type(loader, names.fishLoot());
        MethodHandle itemId = Handles.virtual(loot, "getItemID", String.class);
        MethodHandle lootName = Handles.virtual(loot, "getName", String.class);
        MethodHandle rarity = Handles.virtual(loot, "getRarity", String.class);
        Class<?> manager = Handles.type(loader, names.lootManager());
        MethodHandle lootId = Handles.virtual(manager, "getId", String.class);
        Loot reader = new Loot(manager, lootId, itemId, lootName, rarity);
        onUnbind(() -> {
            streaks.clear();
            layout.set(null);
        });

        AtomicReference<MethodHandle> sizeOf = new AtomicReference<>();
        feature(F_SIZE, () -> {
            Field f = loot.getDeclaredField("size");
            if (f.getType() != int.class) {
                throw new NoSuchFieldException(loot.getName() + ".size is not an int");
            }
            f.setAccessible(true);
            MethodHandle mh = MethodHandles.lookup().unreflectGetter(f);
            sizeOf.set(mh.asType(mh.type().generic()));
        });

        AtomicReference<Book> book = new AtomicReference<>();
        feature(F_PAGES, () -> {
            Class<?> bookClass = Handles.type(loader, names.book());
            Class<?> habitat = Handles.type(loader, names.habitat());
            Class<?> entry = Handles.type(loader, names.fishEntry());
            book.set(new Book(
                    Handles.statik(bookClass, "getMasterMergedBook", bookClass),
                    Handles.virtualByName(bookClass, "getHabitats", 0),
                    generic(MethodHandles.publicLookup().findGetter(habitat, "ZoneName", String.class)),
                    Handles.virtual(bookClass, "getFishByHabitat", List.class, String.class),
                    Handles.virtual(entry, "id", String.class),
                    Handles.virtual(entry, "isItem", boolean.class)));
        });

        feature(F_CATCH, () -> {
            Class<?> event = Handles.type(loader, names.caughtEvent());
            Catch c = new Catch(
                    Handles.virtual(event, "getLoot", loot),
                    Handles.virtualByName(event, "getPlayer", 0),
                    Handles.virtual(event, "isLegendary", boolean.class),
                    Handles.virtual(event, "getPerformance", float.class),
                    Handles.virtual(event, "getPRating", String.class),
                    reader, sizeOf.get(), book.get());
            listen(event, false, e -> guard(F_CATCH, () -> onCaught(e, c)));
            Subscription left = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.PLAYER_LEFT), Delivery.ASYNC,
                    ev -> {
                        UUID p = ev.header().player();
                        if (p != null) {
                            streaks.remove(p);
                        }
                    });
            onUnbind(left::close);
        });
        if (!has(F_CATCH)) {
            // pages and size only serve the catch
            for (String f : List.of(F_PAGES, F_SIZE)) {
                if (has(f)) {
                    off(f, "without the catch event");
                }
            }
        }

        feature(F_FAILED, () -> {
            Class<?> event = Handles.type(loader, names.failedEvent());
            MethodHandle player = Handles.virtualByName(event, "getPlayer", 0);
            MethodHandle missed = Handles.virtual(event, "getMissedLoot", loot);
            listen(event, false, e -> guard(F_FAILED, () -> onFailed(e, player, missed, reader)));
        });
        feature(F_CAST, () -> {
            Class<?> event = Handles.type(loader, names.castEvent());
            MethodHandle player = Handles.virtualByName(event, "getPlayer", 0);
            MethodHandle cancelled = Handles.virtual(event, "isCancelled", boolean.class);
            listen(event, true, e -> guard(F_CAST, () -> onCast(e, player, cancelled)));
        });
        feature(F_HOOK, () -> {
            Class<?> event = Handles.type(loader, names.pullEvent());
            MethodHandle player = Handles.virtualByName(event, "getPlayer", 0);
            MethodHandle target = Handles.virtualByName(event, "getTarget", 0);
            MethodHandle cancelled = Handles.virtual(event, "isCancelled", boolean.class);
            listen(event, true, e -> guard(F_HOOK, () -> onHook(e, player, target, cancelled)));
        });
    }

    @Override
    protected void reset() {
        streaks.clear();
        layout.set(null);
        playerRefReaders.clear();
    }

    private void listen(Class<?> event, boolean last, Consumer<Object> consumer) {
        Runnable undo = engine.listen(event, last, consumer);
        onUnbind(undo);
    }

    private static MethodHandle generic(MethodHandle mh) {
        return mh.asType(mh.type().generic());
    }

    /** The loot readers. {@code lootId} is read when the loot is a {@code FishLootManager}, as rolled loot is. */
    private record Loot(Class<?> manager, MethodHandle lootId, MethodHandle itemId, MethodHandle name, MethodHandle rarity) {
        String idOf(Object loot) throws Throwable {
            if (manager.isInstance(loot)) {
                String id = Handles.stringOf(lootId.invoke(loot));
                if (id != null && !id.isEmpty()) {
                    return id;
                }
            }
            // the mod stores the item id for loot that is not in its loot map
            String item = Handles.stringOf(itemId.invoke(loot));
            return item == null || item.isEmpty() ? null : item;
        }
    }

    private record Catch(MethodHandle loot, MethodHandle player, MethodHandle legendary, MethodHandle performance,
                         MethodHandle grade, Loot reader, MethodHandle size, Book book) {
    }

    private record Book(MethodHandle master, MethodHandle habitats, MethodHandle zoneName, MethodHandle fishByHabitat,
                        MethodHandle entryId, MethodHandle entryIsLoot) {
    }

    // ---- callbacks ---------------------------------------------------------------------------------------

    /** A catch, on a common pool thread. Reads the event and its loot only. */
    private void onCaught(Object event, Catch c) throws Throwable {
        if (!wants(EventKind.MOD_TALLY) && !wants(EventKind.MOD_ACTION) && !wants(EventKind.MOD_MILESTONE)) {
            return;
        }
        Object loot = c.loot().invoke(event);
        if (loot == null) {
            return;
        }
        UUID uuid = playerUuid(c.player().invoke(event));
        if (uuid == null) {
            return;
        }
        String id = c.reader().idOf(loot);
        if (id == null) {
            return;
        }
        String rarity = lower(Handles.stringOf(c.reader().rarity().invoke(loot)));
        boolean junk = isJunk(rarity);
        Header h = header(uuid);
        tally(h, junk ? JUNK : CAUGHT, id, 1, 0);
        if (rarity != null) {
            tally(h, RARITY, rarity, 1, 0);
        }
        String grade = lower(Handles.stringOf(c.grade().invoke(event)));
        if (grade != null) {
            tally(h, GRADE, grade, 1, gradeRank(grade));
        }
        long performance = Math.round(Handles.doubleOf(c.performance().invoke(event), -1));
        if (performance > 0) {
            tally(h, PERFORMANCE, null, 0, performance);
        }
        if ("perfect".equals(grade)) {
            tally(h, PERFECT_STREAK, null, 0, streaks.merge(uuid, 1, Integer::sum));
        } else {
            streaks.remove(uuid);
        }
        if (Handles.boolOf(c.legendary().invoke(event), false)) {
            action(h, LEGENDARY, id);
        }
        if (!junk) {
            if (id.startsWith("Golden_")) {
                action(h, GOLDEN, id);
            }
            if (c.size() != null && has(F_SIZE)) {
                guard(F_SIZE, () -> tally(h, SIZE, null, 0, Handles.intOf(c.size().invoke(loot), 0)));
            }
            String name = Handles.stringOf(c.reader().name().invoke(loot));
            discover(h, uuid, CAT_SPECIES, id, name);
            if (c.book() != null && has(F_PAGES)) {
                guard(F_PAGES, () -> pages(h, uuid, id, name, c.book()));
            }
        }
        fact(uuid, "species", Long.toString(services.counters.get(uuid, key(ModKeys.plural(CAT_SPECIES)))));
        fact(uuid, "legendary", Long.toString(services.counters.get(uuid, key(LEGENDARY))));
    }

    /** The page of a species and, when it was the last missing one, its zone. */
    private void pages(Header h, UUID uuid, String id, String name, Book book) throws Throwable {
        Layout l = layout(book);
        if (l == null) {
            return;
        }
        List<String> zones = l.zonesOf(id);
        if (zones.isEmpty()) {
            return;
        }
        discover(h, uuid, CAT_PAGE, id, name);
        for (String zone : zones) {
            boolean complete = true;
            for (String page : l.zones().get(zone)) {
                if (services.counters.get(uuid, ModKeys.key(ID, CAT_PAGE, page)) <= 0) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                discover(h, uuid, CAT_ZONE, zone, zone);
            }
        }
    }

    /** The layout of this bind, read from the mod's merged Almanac on first use. Null while the book is empty. */
    private Layout layout(Book book) throws Throwable {
        Layout l = layout.get();
        if (l != null) {
            return l;
        }
        Object master = book.master().invoke();
        if (master == null) {
            return null;
        }
        Object habitats = book.habitats().invoke(master);
        if (!(habitats instanceof Object[] arr)) {
            return null;
        }
        Map<String, List<String>> zones = new LinkedHashMap<>();
        for (Object habitat : arr) {
            String zone = habitat == null ? null : Handles.stringOf(book.zoneName().invoke(habitat));
            if (zone == null || zone.isEmpty() || zones.containsKey(zone)) {
                continue;
            }
            Object list = book.fishByHabitat().invoke(master, zone);
            List<String> ids = new ArrayList<>();
            if (list instanceof List<?> entries) {
                for (Object e : entries) {
                    // the mod counts only entries that name a loot asset, the zone's title page is not one
                    if (e != null && Handles.boolOf(book.entryIsLoot().invoke(e), false)) {
                        String id = Handles.stringOf(book.entryId().invoke(e));
                        if (id != null && !ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
            }
            if (!ids.isEmpty()) {
                zones.put(zone, List.copyOf(ids));
            }
        }
        if (zones.isEmpty()) {
            return null;
        }
        Layout read = new Layout(Map.copyOf(zones));
        return layout.compareAndSet(null, read) ? read : layout.get();
    }

    /** An escape, on the world thread. Ends the perfect streak. */
    private void onFailed(Object event, MethodHandle player, MethodHandle missed, Loot reader) throws Throwable {
        UUID uuid = playerUuid(player.invoke(event));
        if (uuid == null) {
            return;
        }
        streaks.remove(uuid);
        if (!wants(EventKind.MOD_ACTION)) {
            return;
        }
        Object loot = missed.invoke(event);
        publish(new ModAction(header(uuid), ID, ESCAPED, false, loot == null ? null : reader.idOf(loot), 0));
    }

    /** A cast, on the world thread, after every other listener. */
    private void onCast(Object event, MethodHandle player, MethodHandle cancelled) throws Throwable {
        if (!wants(EventKind.MOD_ACTION) || Handles.boolOf(cancelled.invoke(event), false)) {
            return;
        }
        UUID uuid = engine.playerOf(player.invoke(event));
        if (uuid != null) {
            publish(new ModAction(header(uuid), ID, CAST, false, null, 0));
        }
    }

    /** A hooked entity, on the world thread, after every other listener. PvP when the target is a player. */
    private void onHook(Object event, MethodHandle player, MethodHandle target, MethodHandle cancelled) throws Throwable {
        if (!wants(EventKind.MOD_ACTION) || Handles.boolOf(cancelled.invoke(event), false)) {
            return;
        }
        UUID uuid = engine.playerOf(player.invoke(event));
        if (uuid == null) {
            return;
        }
        UUID victim = engine.playerOf(target.invoke(event));
        publish(new ModAction(header(uuid), ID, HOOKED, victim != null, victim == null ? null : "Player", 0));
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private void tally(Header h, String stat, String id, long amount, long best) {
        if ((amount > 0 || best > 0) && wants(EventKind.MOD_TALLY)) {
            publish(new ModTally(h, ID, stat, id, amount, best));
        }
    }

    private void action(Header h, String action, String id) {
        if (wants(EventKind.MOD_ACTION)) {
            publish(new ModAction(h, ID, action, false, id, 0));
        }
    }

    /** A milestone the player has not reached yet, so the journal sees each once. */
    private void discover(Header h, UUID uuid, String category, String id, String name) {
        if (wants(EventKind.MOD_MILESTONE) && services.counters.get(uuid, ModKeys.key(ID, category, id)) <= 0) {
            publish(new ModMilestone(h, ID, category, id, name));
        }
    }

    /** The uuid of the mod's player object, through its {@code getPlayerRef()}. */
    private UUID playerUuid(Object player) throws Throwable {
        if (player == null) {
            return null;
        }
        MethodHandle mh = playerRefReaders.computeIfAbsent(player.getClass(), AnglersBinding::playerRefReader);
        return mh == null ? null : uuidOf(mh.invoke(player));
    }

    private static MethodHandle playerRefReader(Class<?> type) {
        try {
            return Handles.virtualByName(type, "getPlayerRef", 0);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }

    /** Junk and the one essence the mod files under {@code Essences}. */
    static boolean isJunk(String rarity) {
        return "junk".equals(rarity) || "essences".equals(rarity);
    }

    /** The grade as a rank for the best, perfect 4, great 3, good 2, fail 1, nil and unknown 0. */
    static long gradeRank(String grade) {
        return switch (grade) {
            case "perfect" -> 4;
            case "great" -> 3;
            case "good" -> 2;
            case "fail" -> 1;
            default -> 0;
        };
    }
}
