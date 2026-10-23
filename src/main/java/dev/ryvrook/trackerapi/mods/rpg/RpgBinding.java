package dev.ryvrook.trackerapi.mods.rpg;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.ModChoiceChanged;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The binding to RPG Leveling 0.4.2 ({@code Zuxaw:RPGLeveling}), written from its adapter contract. Everything of
 * the mod is resolved through the plugin's own class loader at bind time, in four feature groups, and nothing of
 * the mod is named at compile time.
 *
 * <p>Two listeners, both {@link java.lang.reflect.Proxy} instances in the mod's loader. The experience listener
 * publishes {@link ModXpGained} with the amount and the cancelled flag as the event holds them when the callback
 * runs, which is before RPG Leveling's own boosts and before any listener registered after this one. The level up
 * listener publishes {@link ModLevelUp} with the max level the last level read saw. Both callbacks arrive on the
 * player's world thread as a queued task of the mod, or on the caller's thread while the player is between worlds,
 * so they only read the event and publish.
 *
 * <p>Two reads, {@code getPlayerLevelInfo(PlayerRef, Store)} and {@code getPlayerClassInfo(PlayerRef, Ref, Store)},
 * always on the player's world thread through {@link RpgPlayers}, never the UUID overloads, which answer level 1
 * and no class for a player inside a world. They run for every online player at bind, for a player when they join,
 * and for everyone every {@value #POLL_SECONDS} seconds. The level read seeds {@code rpg:level} and the fact
 * {@code rpg.level}, so the stats are right from the first minute. The class read keeps the fact {@code rpg.class}
 * and publishes {@link ModChoiceChanged} only when the class or its tier differs from the last read of this
 * session, the first sighting seeds the discovery counter without an event.
 */
public final class RpgBinding extends AbstractBinding {
    public static final String ID = "rpg";
    public static final String PLUGIN_ID = "Zuxaw:RPGLeveling";
    public static final String NAME = "RPG Leveling";
    /** The slot of the class pick in {@link ModChoiceChanged}. */
    public static final String SLOT_CLASS = "class";

    public static final String F_XP = "experience listener";
    public static final String F_LEVEL_UP = "level up listener";
    public static final String F_LEVEL = "level reads";
    public static final String F_CLASS = "class reads";

    /** How often every online player's level and class are read on their world thread. */
    static final long POLL_SECONDS = 30;

    /**
     * The class names of the mod's API. Production uses {@link #PRODUCTION}, a test points them at a fake with the
     * same shape, or at a missing class to get a partial bind.
     */
    public record Names(String api, String xpListener, String xpEvent, String xpSource, String levelListener,
                        String levelEvent, String levelInfo, String classInfo) {
        /** The names under one package, with the two info classes nested in the API class. */
        public static Names inPackage(String pkg) {
            return new Names(pkg + ".RPGLevelingAPI", pkg + ".ExperienceGainedListener", pkg + ".ExperienceGainedEvent",
                    pkg + ".XPSource", pkg + ".LevelUpListener", pkg + ".LevelUpEvent",
                    pkg + ".RPGLevelingAPI$PlayerLevelInfo", pkg + ".RPGLevelingAPI$PlayerClassInfo");
        }

        public static final Names PRODUCTION = inPackage("org.zuxaw.plugin.api");
    }

    private final Names names;
    private final RpgPlayers players;

    /** The mod's API object while bound, the receiver of every handle, cleared at unbind. */
    private volatile Object api;
    private volatile MethodHandle levelInfo;
    private volatile MethodHandle infoLevel;
    private volatile MethodHandle infoMaxLevel;
    private volatile MethodHandle classInfo;
    private volatile MethodHandle classHas;
    private volatile MethodHandle classId;
    private volatile MethodHandle className;
    private volatile MethodHandle classTier;
    /** The level cap the last level read saw, 0 until one did. One value, the cap is server wide. */
    private volatile int maxLevel;
    /** The class and tier last read per online player, so a change is published once. */
    private final Map<UUID, String> lastClass = new ConcurrentHashMap<>();

    public RpgBinding() {
        this(Names.PRODUCTION, new EngineRpgPlayers());
    }

    RpgBinding(Names names, RpgPlayers players) {
        super(ID, PLUGIN_ID, NAME);
        this.names = names;
        this.players = players;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_XP_GAINED, EventKind.MOD_LEVEL_UP, EventKind.MOD_CHOICE_CHANGED);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.max(ModKeys.key(ID, ModKeys.LEVEL), "Level"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.LEVEL_UPS), "Level ups"),
                StatSpec.discovery(ModKeys.key(ID, ModKeys.MAX_LEVEL_REACHED), "Max level reached", "Track"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.XP), "XP").unit("xp"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.XP), "XP by source", "Source").unit("xp"),
                StatSpec.discovery(ModKeys.key(ID, SLOT_CLASS), "Class picked", "Class"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(SLOT_CLASS)), "Classes picked"));
    }

    // ---- bind --------------------------------------------------------------------------------------------

    @Override
    protected void resolve(Object plugin) throws Throwable {
        Class<?> apiType = Handles.type(loader, names.api());
        MethodHandle available = Handles.statik(apiType, "isAvailable", boolean.class);
        MethodHandle get = Handles.statik(apiType, "get", apiType);
        if (!Handles.boolOf(available.invoke(), false)) {
            throw new IllegalStateException("RPGLevelingAPI.isAvailable() is false");
        }
        Object api = get.invoke();
        if (api == null) {
            throw new IllegalStateException("RPGLevelingAPI.get() answered null");
        }
        this.api = api;
        onUnbind(this::clear); // the first step registered runs last, after the unregistrations below

        feature(F_XP, () -> listenXp(apiType, api));
        feature(F_LEVEL_UP, () -> listenLevelUps(apiType, api));
        feature(F_LEVEL, () -> {
            Class<?> info = Handles.type(loader, names.levelInfo());
            MethodHandle read = Handles.virtualByName(apiType, "getPlayerLevelInfo", 2);
            MethodHandle level = Handles.virtual(info, "getLevel", int.class);
            MethodHandle max = Handles.virtual(info, "getMaxLevel", int.class);
            levelInfo = read;
            infoLevel = level;
            infoMaxLevel = max;
        });
        feature(F_CLASS, () -> {
            Class<?> info = Handles.type(loader, names.classInfo());
            MethodHandle read = Handles.virtualByName(apiType, "getPlayerClassInfo", 3);
            MethodHandle has = Handles.virtual(info, "hasClass", boolean.class);
            MethodHandle id = Handles.virtual(info, "getClassId", String.class);
            MethodHandle name = Handles.virtual(info, "getDisplayName", String.class);
            MethodHandle tier = Handles.virtual(info, "getSelectedClassTier", int.class);
            classInfo = read;
            classHas = has;
            classId = id;
            className = name;
            classTier = tier;
        });

        if (has(F_LEVEL) || has(F_CLASS)) {
            Subscription joins = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.PLAYER_JOINED), Delivery.ASYNC,
                    e -> onJoined(e.player()));
            onUnbind(joins::close);
            ScheduledFuture<?> poll = services.timer.scheduleAtFixedRate(this::pollSafely, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            onUnbind(() -> poll.cancel(false));
            players.forEachOnline(this::visit);
        }
    }

    private void listenXp(Class<?> apiType, Object api) throws Throwable {
        Class<?> listener = Handles.type(loader, names.xpListener());
        Class<?> event = Handles.type(loader, names.xpEvent());
        Class<?> source = Handles.type(loader, names.xpSource());
        MethodHandle register = Handles.virtual(apiType, "registerExperienceGainedListener", void.class, listener);
        MethodHandle unregister = Handles.virtual(apiType, "unregisterExperienceGainedListener", boolean.class, listener);
        MethodHandle player = Handles.virtualByName(event, "getPlayer", 0);
        MethodHandle amount = Handles.virtual(event, "getXpAmount", double.class);
        MethodHandle cancelled = Handles.virtual(event, "isCancelled", boolean.class);
        MethodHandle getSource = Handles.virtual(event, "getSource", source);
        MethodHandle sourceName = Handles.virtual(source, "getName", String.class);
        Object proxy = listenerProxy(listener, "onExperienceGained", "TrackerAPI RPG Leveling experience listener",
                e -> guard(F_XP, () -> onXp(e, player, amount, cancelled, getSource, sourceName)));
        register.invoke(api, proxy);
        onUnbind(() -> unregister(unregister, api, proxy));
    }

    private void listenLevelUps(Class<?> apiType, Object api) throws Throwable {
        Class<?> listener = Handles.type(loader, names.levelListener());
        Class<?> event = Handles.type(loader, names.levelEvent());
        MethodHandle register = Handles.virtual(apiType, "registerLevelUpListener", void.class, listener);
        MethodHandle unregister = Handles.virtual(apiType, "unregisterLevelUpListener", boolean.class, listener);
        MethodHandle player = Handles.virtualByName(event, "getPlayer", 0);
        MethodHandle oldLevel = Handles.virtual(event, "getOldLevel", int.class);
        MethodHandle newLevel = Handles.virtual(event, "getNewLevel", int.class);
        Object proxy = listenerProxy(listener, "onLevelUp", "TrackerAPI RPG Leveling level up listener",
                e -> guard(F_LEVEL_UP, () -> onLevelUp(e, player, oldLevel, newLevel)));
        register.invoke(api, proxy);
        onUnbind(() -> unregister(unregister, api, proxy));
    }

    /**
     * A proxy of a one method listener interface. RPG Leveling keeps listeners in a {@code CopyOnWriteArrayList},
     * so {@code unregister} calls {@code equals}, and its failure log may call {@code toString}, both answered here.
     */
    private static Object listenerProxy(Class<?> iface, String method, String label, Consumer<Object> target) {
        return Handles.proxy(iface, (p, m, args) -> {
            int n = args == null ? 0 : args.length;
            if (m.getName().equals(method) && n == 1) {
                target.accept(args[0]);
                return null;
            }
            return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> n == 1 && p == args[0];
                case "toString" -> label;
                default -> null;
            };
        });
    }

    private static void unregister(MethodHandle unregister, Object api, Object proxy) {
        try {
            unregister.invoke(api, proxy);
        } catch (Throwable ignored) {
            // the mod is gone
        }
    }

    private void clear() {
        api = null;
        levelInfo = null;
        infoLevel = null;
        infoMaxLevel = null;
        classInfo = null;
        classHas = null;
        classId = null;
        className = null;
        classTier = null;
        maxLevel = 0;
    }

    @Override
    protected void reset() {
        lastClass.clear();
    }

    // ---- listener callbacks, the mod's thread ------------------------------------------------------------

    private void onXp(Object event, MethodHandle player, MethodHandle amount, MethodHandle cancelled, MethodHandle getSource,
                      MethodHandle sourceName) throws Throwable {
        if (!wants(EventKind.MOD_XP_GAINED)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        if (uuid == null || Handles.boolOf(cancelled.invoke(event), false)) {
            return;
        }
        double xp = Handles.doubleOf(amount.invoke(event), 0);
        if (!(xp > 0) || Double.isInfinite(xp)) {
            return;
        }
        Object source = getSource.invoke(event);
        String name = source == null ? null : Handles.stringOf(sourceName.invoke(source));
        publish(new ModXpGained(header(uuid), ID, ModKeys.PLAYER_TRACK, xp, name));
    }

    private void onLevelUp(Object event, MethodHandle player, MethodHandle oldLevel, MethodHandle newLevel) throws Throwable {
        if (!wants(EventKind.MOD_LEVEL_UP)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        if (uuid == null) {
            return;
        }
        int from = Handles.intOf(oldLevel.invoke(event), 0);
        int to = Handles.intOf(newLevel.invoke(event), 0);
        publish(new ModLevelUp(header(uuid), ID, ModKeys.PLAYER_TRACK, from, to, maxLevel));
        if (to > 0) {
            fact(uuid, ModKeys.LEVEL, Integer.toString(to));
        }
    }

    // ---- reads ---------------------------------------------------------------------------------------------

    /** Dispatch thread. A player is ready in their first world, read them there. */
    void onJoined(UUID uuid) {
        if (uuid == null || !(has(F_LEVEL) || has(F_CLASS))) {
            return;
        }
        players.one(uuid, this::visit);
    }

    /** Timer thread. Posts the reads of every online player and drops what is kept about players who left. */
    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException | LinkageError e) {
            log.at(Level.FINE).withCause(e).log("TrackerAPI could not poll RPG Leveling");
        }
    }

    void poll() {
        if (!isBound()) {
            return;
        }
        lastClass.keySet().removeIf(u -> !online(u));
        players.forEachOnline(this::visit);
    }

    /** World thread. Reads one player's level and class through the mod. */
    void visit(UUID uuid, Object playerRef, Object ref, Object store) {
        Object api = this.api;
        if (uuid == null || api == null || !isBound()) {
            return;
        }
        guard(F_LEVEL, () -> readLevel(api, uuid, playerRef, store));
        guard(F_CLASS, () -> readClass(api, uuid, playerRef, ref, store));
    }

    private void readLevel(Object api, UUID uuid, Object playerRef, Object store) throws Throwable {
        MethodHandle read = levelInfo;
        if (read == null) {
            return;
        }
        Object info = read.invoke(api, playerRef, store);
        if (info == null) {
            return;
        }
        int level = Handles.intOf(infoLevel.invoke(info), 0);
        int max = Handles.intOf(infoMaxLevel.invoke(info), 0);
        if (max > 0) {
            maxLevel = max;
        }
        if (level <= 0 || !online(uuid)) {
            return;
        }
        String key = ModKeys.key(ID, ModKeys.LEVEL);
        services.counters.raise(uuid, key, level);
        fact(uuid, ModKeys.LEVEL, Integer.toString(level));
        if (max > 0 && level >= max) {
            services.counters.discover(uuid, ModKeys.key(ID, ModKeys.MAX_LEVEL_REACHED), ModKeys.PLAYER_TRACK, null);
        }
    }

    private void readClass(Object api, UUID uuid, Object playerRef, Object ref, Object store) throws Throwable {
        MethodHandle read = classInfo;
        if (read == null) {
            return;
        }
        Object info = read.invoke(api, playerRef, ref, store);
        if (info == null || !online(uuid)) {
            return;
        }
        if (!Handles.boolOf(classHas.invoke(info), false)) {
            if (lastClass.remove(uuid) != null) {
                fact(uuid, SLOT_CLASS, null);
            }
            return;
        }
        String id = text(Handles.stringOf(classId.invoke(info)));
        if (id == null) {
            return;
        }
        String name = text(Handles.stringOf(className.invoke(info)));
        int tier = Math.max(0, Handles.intOf(classTier.invoke(info), 0));
        String seen = id + '\u0000' + tier;
        String before = lastClass.put(uuid, seen);
        if (seen.equals(before)) {
            return;
        }
        fact(uuid, SLOT_CLASS, name == null ? id : name);
        if (before == null) {
            // the first read of this session says what the player has, not that they changed it
            services.counters.discover(uuid, ModKeys.key(ID, SLOT_CLASS), id, ModKeys.key(ID, ModKeys.plural(SLOT_CLASS)));
            return;
        }
        if (wants(EventKind.MOD_CHOICE_CHANGED)) {
            publish(new ModChoiceChanged(header(uuid), ID, SLOT_CLASS, id, name, tier));
        }
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    /** The mod's API object while bound, for the test of the loader isolation. */
    Object apiForTest() {
        return api;
    }
}
