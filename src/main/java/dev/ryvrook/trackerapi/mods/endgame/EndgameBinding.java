package dev.ryvrook.trackerapi.mods.endgame;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.DungeonRun;
import dev.ryvrook.trackerapi.api.event.ModBossDefeated;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.facts.PlayerRegistry;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The binding to Endgame and QoL. Boss kills, achievement unlocks and dungeon entries come from the plugin's own
 * {@code GameEventBus}, which takes a plain {@code Consumer} and returns an engine {@code Registration}. Trials
 * come from the wave arena callbacks through a proxy. Bestiary discoveries, runes found and the combo personal
 * best are read from the per player component at bind and when a player joins, a concurrent map lookup the
 * contract allows from any thread.
 *
 * <p>Nothing here touches the mod's database, its leaderboard store, its telemetry or any write method.
 */
public final class EndgameBinding extends AbstractBinding {
    public static final String ID = "endgame";
    public static final String PLUGIN_ID = "Config:Endgame&QoL";
    public static final String NAME = "Endgame and QoL";

    /** The engine type every registration of the mod returns, the same class in every loader. */
    static final String REGISTRATION = "com.hypixel.hytale.registry.Registration";

    public static final String F_BOSSES = "boss kill events";
    public static final String F_ACHIEVEMENTS = "achievement events";
    public static final String F_DUNGEONS = "dungeon entry events";
    public static final String F_TRIALS = "trial callbacks";
    public static final String F_SEEDS = "player data seeds";

    static final String CAT_ACHIEVEMENT = "achievement";
    static final String CAT_BESTIARY = "bestiary";
    static final String CAT_RUNE = "rune";
    static final String CAT_WAVE = "wave";
    static final String COMBO_BEST = "combo_best";
    static final String WAVE_BEST = "wave_best";

    /** The class names of the mod's API, under one package root in production and under the fakes in tests. */
    public record Names(String plugin, String bus, String bossKill, String achievementUnlock, String dungeonEnter,
                        String arenaApi, String arenaCallbacks, String component, String bestiaryState,
                        String bestiaryEntry, String runeState) {
        public static Names inPackage(String root) {
            return new Names(root + ".plugin.EndgameQoL",
                    root + ".plugin.events.domain.GameEventBus",
                    root + ".plugin.events.domain.GameEvent$BossKillEvent",
                    root + ".plugin.events.domain.GameEvent$AchievementUnlockEvent",
                    root + ".plugin.events.domain.GameEvent$DungeonEnterEvent",
                    root + ".wavearena.WaveArenaAPI",
                    root + ".wavearena.WaveArenaCallbacks",
                    root + ".plugin.components.PlayerEndgameComponent",
                    root + ".plugin.config.BestiaryData$PlayerBestiaryState",
                    root + ".plugin.config.BestiaryData$NPCEntry",
                    root + ".plugin.config.RuneCollectionState");
        }

        public static final Names PRODUCTION = inPackage("endgame");
    }

    /** A callback body that receives the mod's event. */
    @FunctionalInterface
    private interface EventBody {
        void accept(Object event) throws Throwable;
    }

    private final Names names;
    final TrialTimes trials = new TrialTimes();

    private volatile Object plugin;
    private MethodHandle getComponent;
    private MethodHandle getBestiary;
    private MethodHandle getEntries;
    private MethodHandle isDiscovered;
    private MethodHandle getRunes;
    private MethodHandle getFound;
    private MethodHandle getComboBest;

    public EndgameBinding() {
        this(Names.PRODUCTION);
    }

    public EndgameBinding(Names names) {
        super(ID, PLUGIN_ID, NAME);
        this.names = names;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_BOSS_DEFEATED, EventKind.MOD_MILESTONE, EventKind.DUNGEON_RUN);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(ModKeys.key(ID, ModKeys.BOSSES), "Boss kills"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.BOSSES), "Boss kills by boss", "Boss"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_ACHIEVEMENT)), "Achievements"),
                StatSpec.perId(ModKeys.key(ID, CAT_ACHIEVEMENT), "Achievements by id", "Achievement"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.RUNS), "Dungeon entries"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.RUNS), "Dungeon entries by dungeon", "Dungeon"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.CLEARED), "Trials completed"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.CLEARED), "Trials completed by arena", "Arena"),
                StatSpec.min(ModKeys.key(ID, ModKeys.FASTEST), "Fastest trial", "millis"),
                new StatSpec(ModKeys.key(ID, ModKeys.FASTEST), "Fastest trial by arena", StatSpec.Aggregate.MIN, true, "Arena", false, "millis"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.FAILED), "Trials failed"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.FAILED), "Trials failed by arena", "Arena"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_WAVE)), "Waves cleared"),
                StatSpec.perId(ModKeys.key(ID, CAT_WAVE), "Waves cleared by arena and wave", "Wave"),
                StatSpec.max(ModKeys.key(ID, WAVE_BEST), "Highest wave"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_BESTIARY)), "Bestiary entries"),
                StatSpec.perId(ModKeys.key(ID, CAT_BESTIARY), "Bestiary by creature", "Creature"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_RUNE)), "Runes found"),
                StatSpec.perId(ModKeys.key(ID, CAT_RUNE), "Runes by id", "Rune"),
                StatSpec.max(ModKeys.key(ID, COMBO_BEST), "Best combo"));
    }

    @Override
    protected void resolve(Object plugin) throws Throwable {
        Class<?> pluginType = Handles.type(loader, names.plugin());
        Class<?> registration = Handles.type(loader, REGISTRATION);
        this.plugin = plugin;
        onUnbind(() -> {
            this.plugin = null;
            getComponent = null;
            getBestiary = null;
            getEntries = null;
            isDiscovered = null;
            getRunes = null;
            getFound = null;
            getComboBest = null;
            trials.clear();
        });

        // The plugin's own bus, one per plugin instance. A failure here leaves the three bus features missing.
        Object bus = null;
        MethodHandle subscribe = null;
        Throwable busFailure = null;
        try {
            Class<?> busType = Handles.type(loader, names.bus());
            MethodHandle getBus = Handles.virtual(pluginType, "getGameEventBus", busType);
            bus = getBus.invoke(plugin);
            subscribe = Handles.virtual(busType, "subscribe", registration, Class.class, Consumer.class);
            if (bus == null) {
                busFailure = new IllegalStateException("the game event bus is null");
            }
        } catch (Throwable t) {
            busFailure = t;
        }
        final Object theBus = bus;
        final MethodHandle theSubscribe = subscribe;
        final Throwable theBusFailure = busFailure;

        feature(F_BOSSES, () -> {
            if (theBusFailure != null) {
                throw theBusFailure;
            }
            Class<?> event = Handles.type(loader, names.bossKill());
            MethodHandle credited = Handles.virtual(event, "creditedPlayers", Set.class);
            MethodHandle bossTypeId = Handles.virtual(event, "bossTypeId", String.class);
            subscribeBus(theSubscribe, theBus, event, F_BOSSES, e -> onBossKill(e, credited, bossTypeId));
        });
        feature(F_ACHIEVEMENTS, () -> {
            if (theBusFailure != null) {
                throw theBusFailure;
            }
            Class<?> event = Handles.type(loader, names.achievementUnlock());
            MethodHandle player = Handles.virtual(event, "playerUuid", UUID.class);
            MethodHandle id = Handles.virtual(event, "achievementId", String.class);
            MethodHandle name = Handles.virtual(event, "achievementName", String.class);
            subscribeBus(theSubscribe, theBus, event, F_ACHIEVEMENTS, e -> onAchievement(e, player, id, name));
        });
        feature(F_DUNGEONS, () -> {
            if (theBusFailure != null) {
                throw theBusFailure;
            }
            Class<?> event = Handles.type(loader, names.dungeonEnter());
            MethodHandle player = Handles.virtual(event, "playerUuid", UUID.class);
            MethodHandle type = Handles.virtual(event, "dungeonType", String.class);
            subscribeBus(theSubscribe, theBus, event, F_DUNGEONS, e -> onDungeonEnter(e, player, type));
        });
        feature(F_TRIALS, () -> {
            Class<?> api = Handles.type(loader, names.arenaApi());
            Class<?> callbacks = Handles.type(loader, names.arenaCallbacks());
            MethodHandle available = Handles.statik(api, "isAvailable", boolean.class);
            MethodHandle register = Handles.statik(api, "registerCallbacks", registration, callbacks);
            if (!Handles.boolOf(available.invoke(), false)) {
                throw new IllegalStateException("the wave arena engine is not available");
            }
            Object proxy = Handles.proxy(callbacks, (p, m, args) -> {
                switch (m.getName()) {
                    case "onWaveStart" -> guard(F_TRIALS, () -> onWaveStart(args));
                    case "onWaveClear" -> guard(F_TRIALS, () -> onWaveClear(args));
                    case "onArenaCompleted" -> guard(F_TRIALS, () -> onArenaEnd(args, DungeonRun.Phase.COMPLETED));
                    case "onArenaFailed" -> guard(F_TRIALS, () -> onArenaEnd(args, DungeonRun.Phase.FAILED));
                    case "onCleanup" -> guard(F_TRIALS, () -> trials.forget(uuidOf(args[0])));
                    case "onOwnerChanged" -> guard(F_TRIALS, () -> trials.move(uuidOf(args[0]), uuidOf(args[1])));
                    case "hashCode" -> {
                        return System.identityHashCode(p);
                    }
                    case "equals" -> {
                        return p == args[0];
                    }
                    case "toString" -> {
                        return "TrackerAPI endgame trial callbacks";
                    }
                    default -> {
                        // countdown, mob spawned, mob killed, timed out and zone entry are not tracked
                    }
                }
                return null;
            });
            Object reg = register.invoke(proxy);
            onUnbind(() -> unregister(reg));
        });
        boolean seeds = feature(F_SEEDS, () -> {
            Class<?> component = Handles.type(loader, names.component());
            Class<?> bestiary = Handles.type(loader, names.bestiaryState());
            Class<?> entry = Handles.type(loader, names.bestiaryEntry());
            Class<?> runes = Handles.type(loader, names.runeState());
            getComponent = Handles.virtual(pluginType, "getPlayerComponent", component, UUID.class);
            getBestiary = Handles.virtual(component, "getBestiaryState", bestiary);
            getEntries = Handles.virtual(bestiary, "getEntries", Map.class);
            isDiscovered = Handles.virtual(entry, "isDiscovered", boolean.class);
            getRunes = Handles.virtual(component, "getRuneCollection", runes);
            getFound = Handles.virtual(runes, "getFound", Map.class);
            getComboBest = Handles.virtual(component, "getComboPersonalBest", int.class);
            Subscription joins = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.PLAYER_JOINED), Delivery.ASYNC,
                    e -> guard(F_SEEDS, () -> seed(e.player())));
            onUnbind(joins::close);
        });
        if (seeds) {
            for (PlayerRegistry.Online o : services.players.all()) {
                guard(F_SEEDS, () -> seed(o.uuid));
            }
        }
    }

    // ---- the bus -----------------------------------------------------------------------------------------

    private void subscribeBus(MethodHandle subscribe, Object bus, Class<?> event, String feature, EventBody body) throws Throwable {
        Consumer<Object> consumer = e -> guard(feature, () -> body.accept(e));
        Object reg = subscribe.invoke(bus, event, consumer);
        onUnbind(() -> unregister(reg));
    }

    /** Runs the unregister of an engine registration, through its own class so a loader mismatch cannot bite. */
    private void unregister(Object registration) {
        if (registration == null) {
            return;
        }
        try {
            Handles.virtualByName(registration.getClass(), "unregister", 0).invoke(registration);
        } catch (Throwable t) {
            if (log != null) {
                log.at(Level.FINE).withCause(t).log("TrackerAPI %s binding could not unregister from the mod", NAME);
            }
        }
    }

    private void onBossKill(Object event, MethodHandle credited, MethodHandle bossTypeId) throws Throwable {
        if (!wants(EventKind.MOD_BOSS_DEFEATED)) {
            return;
        }
        Object players = credited.invoke(event);
        if (!(players instanceof Collection<?> c) || c.isEmpty()) {
            return;
        }
        String boss = Handles.stringOf(bossTypeId.invoke(event));
        List<UUID> participants = new ArrayList<>(c.size());
        for (Object o : c) {
            UUID u = uuidOf(o);
            if (u != null) {
                participants.add(u);
            }
        }
        if (participants.isEmpty()) {
            return;
        }
        List<UUID> all = List.copyOf(participants);
        for (UUID u : all) {
            publish(new ModBossDefeated(header(u), ID, boss, null, all));
        }
    }

    private void onAchievement(Object event, MethodHandle player, MethodHandle id, MethodHandle name) throws Throwable {
        if (!wants(EventKind.MOD_MILESTONE)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        String achievement = Handles.stringOf(id.invoke(event));
        if (uuid == null || achievement == null) {
            return;
        }
        publish(new ModMilestone(header(uuid), ID, CAT_ACHIEVEMENT, achievement, Handles.stringOf(name.invoke(event))));
    }

    private void onDungeonEnter(Object event, MethodHandle player, MethodHandle type) throws Throwable {
        if (!wants(EventKind.DUNGEON_RUN)) {
            return;
        }
        UUID uuid = uuidOf(player.invoke(event));
        if (uuid == null) {
            return;
        }
        String dungeon = Handles.stringOf(type.invoke(event));
        publish(new DungeonRun(header(uuid), ID, dungeon, DungeonRun.Phase.ENTERED, null, 0, 0L));
    }

    // ---- trials ------------------------------------------------------------------------------------------

    /** {@code onWaveStart(owner, arenaId, wave, totalWaves)}. The first wave starts the clock. */
    private void onWaveStart(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        if (Handles.intOf(args[2], 0) <= 1) {
            trials.start(uuidOf(args[0]));
        }
    }

    /** {@code onWaveClear(owner, arenaId, wave, totalWaves)}. */
    private void onWaveClear(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        UUID owner = uuidOf(args[0]);
        String arena = Handles.stringOf(args[1]);
        int wave = Handles.intOf(args[2], 0);
        if (owner == null || arena == null || wave <= 0) {
            return;
        }
        if (wants(EventKind.MOD_MILESTONE)) {
            publish(new ModMilestone(header(owner), ID, CAT_WAVE, arena + "/" + wave, arena + " wave " + wave));
        }
        services.counters.raise(owner, Keys.of(ID, WAVE_BEST), wave);
    }

    /** {@code onArenaCompleted(owner, arenaId, waves)} or {@code onArenaFailed(owner, arenaId, wave, reason)}. */
    private void onArenaEnd(Object[] args, DungeonRun.Phase phase) {
        if (args == null || args.length < 2) {
            return;
        }
        UUID owner = uuidOf(args[0]);
        String arena = Handles.stringOf(args[1]);
        long duration = trials.end(owner);
        if (owner == null || !wants(EventKind.DUNGEON_RUN)) {
            return;
        }
        publish(new DungeonRun(header(owner), ID, arena, phase, null, 0, duration));
    }

    // ---- seeds -------------------------------------------------------------------------------------------

    /**
     * Reads the player's component and publishes what TrackerAPI has not seen yet: each discovered bestiary
     * entry and each rune found as a milestone, and the combo personal best raised into its counter. A player
     * without a component yet, or offline, is skipped.
     */
    private void seed(UUID uuid) throws Throwable {
        Object p = plugin;
        MethodHandle component = getComponent;
        if (uuid == null || p == null || component == null || !online(uuid)) {
            return;
        }
        Object comp = component.invoke(p, uuid);
        if (comp == null) {
            return;
        }
        if (wants(EventKind.MOD_MILESTONE)) {
            Object bestiary = getBestiary.invoke(comp);
            Object entries = bestiary == null ? null : getEntries.invoke(bestiary);
            if (entries instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String id = Handles.stringOf(e.getKey());
                    if (id == null || e.getValue() == null || !Handles.boolOf(isDiscovered.invoke(e.getValue()), false)) {
                        continue;
                    }
                    if (!seen(uuid, CAT_BESTIARY, id)) {
                        publish(new ModMilestone(header(uuid), ID, CAT_BESTIARY, id, null));
                    }
                }
            }
            Object runes = getRunes.invoke(comp);
            Object found = runes == null ? null : getFound.invoke(runes);
            if (found instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    String id = Handles.stringOf(k);
                    if (id != null && !seen(uuid, CAT_RUNE, id)) {
                        publish(new ModMilestone(header(uuid), ID, CAT_RUNE, id, null));
                    }
                }
            }
        }
        int best = Handles.intOf(getComboBest.invoke(comp), 0);
        if (best > 0) {
            services.counters.raise(uuid, Keys.of(ID, COMBO_BEST), best);
        }
    }

    /** True when the player's counter for this milestone is already set. */
    private boolean seen(UUID uuid, String category, String id) {
        return services.counters.get(uuid, Keys.of(Keys.of(ID, category), id)) > 0;
    }
}
