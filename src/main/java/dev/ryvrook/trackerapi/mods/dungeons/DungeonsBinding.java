package dev.ryvrook.trackerapi.mods.dungeons;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.DungeonRun;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ModBossDefeated;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The binding to Major Dungeons, through the dungeon framework API the mod bundles. The framework holds one API
 * object per plugin load behind a static provider, and its listeners are plain {@link Consumer}s, so the binding
 * adds four lambdas and removes the same instances at unbind. Entered, Completed and Exited become
 * {@link DungeonRun} phases through {@link RunTable}, a death inside a tracked world (TrackerAPI's own
 * {@link EntityDied}) turns the following Exited into FAILED, and a boss kill becomes one
 * {@link ModBossDefeated} per attacker.
 */
public final class DungeonsBinding extends AbstractBinding {
    public static final String ID = "dungeons";
    public static final String PLUGIN_ID = "MAJOR76:MajorDungeons";
    public static final String F_CORE = "dungeon api";
    public static final String F_RUNS = "run listeners";
    public static final String F_BOSSES = "boss listener";
    /** The counter of the largest party a player cleared with. */
    public static final String PARTY_BEST = "party_best";
    static final String DEFAULT_MODE = "Default";

    /** The class names of the framework API, the production set and a test override. */
    public record Names(String provider, String api, String entered, String exited, String completed, String bossKilled) {
        public static final Names PRODUCTION = new Names(
                "com.danbagh.dungeonframework.api.DungeonFrameworkAPIProvider",
                "com.danbagh.dungeonframework.api.DungeonFrameworkAPI",
                "com.danbagh.dungeonframework.api.DungeonEnteredEvent",
                "com.danbagh.dungeonframework.api.DungeonExitedEvent",
                "com.danbagh.dungeonframework.api.DungeonCompletedEvent",
                "com.danbagh.dungeonframework.api.BossKilledEvent");
    }

    /** The four accessors every run event record has. */
    private record RunReader(MethodHandle player, MethodHandle world, MethodHandle dungeon, MethodHandle mode) {
        static RunReader of(Class<?> event) throws ReflectiveOperationException {
            return new RunReader(Handles.virtual(event, "playerUuid", UUID.class),
                    Handles.virtual(event, "worldName", String.class),
                    Handles.virtual(event, "dungeonId", String.class),
                    Handles.virtual(event, "modeName", String.class));
        }
    }

    private final Names names;
    private final RunTable runs = new RunTable();
    /** The clock of the run durations, replaced by the tests. */
    volatile LongSupplier clock = System::currentTimeMillis;
    private volatile Object api;

    public DungeonsBinding() {
        this(Names.PRODUCTION);
    }

    public DungeonsBinding(Names names) {
        super(ID, PLUGIN_ID, "Major Dungeons");
        this.names = names;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.DUNGEON_RUN, EventKind.MOD_BOSS_DEFEATED);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(ModKeys.key(ID, ModKeys.RUNS), "Dungeon runs"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.RUNS), "Runs by dungeon", "Dungeon"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.CLEARED), "Dungeons cleared"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.CLEARED), "Clears by dungeon", "Dungeon"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.CLEARED_MODE), "Clears by mode", "Mode"),
                StatSpec.min(ModKeys.key(ID, ModKeys.FASTEST), "Fastest clear", "millis"),
                new StatSpec(ModKeys.key(ID, ModKeys.FASTEST), "Fastest clear by dungeon", StatSpec.Aggregate.MIN, true, "Dungeon", false, "millis"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.FAILED), "Runs failed"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.FAILED), "Failures by dungeon", "Dungeon"),
                StatSpec.max(ModKeys.key(ID, PARTY_BEST), "Largest party"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.BOSSES), "Bosses defeated"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.BOSSES), "Bosses by type", "Boss"));
    }

    @Override
    protected void resolve(Object plugin) throws Throwable {
        Class<?>[] apiType = new Class<?>[1];
        boolean core = feature(F_CORE, () -> {
            Class<?> provider = Handles.type(loader, names.provider());
            apiType[0] = Handles.type(loader, names.api());
            MethodHandle get = Handles.statik(provider, "get", apiType[0]);
            Handles.virtual(apiType[0], "isDungeonWorld", boolean.class, String.class);
            Handles.virtual(apiType[0], "getDungeonId", String.class, String.class);
            Handles.virtual(apiType[0], "getDungeonMode", String.class, String.class);
            Object found = get.invoke();
            if (found == null) {
                throw new IllegalStateException("the dungeon framework API is not initialised");
            }
            api = found;
            onUnbind(() -> api = null);
        });
        if (!core) {
            return;
        }
        feature(F_RUNS, () -> {
            RunReader entered = RunReader.of(Handles.type(loader, names.entered()));
            RunReader exited = RunReader.of(Handles.type(loader, names.exited()));
            RunReader completed = RunReader.of(Handles.type(loader, names.completed()));
            MethodHandle addEntered = Handles.virtual(apiType[0], "addDungeonEnteredListener", void.class, Consumer.class);
            MethodHandle removeEntered = Handles.virtual(apiType[0], "removeDungeonEnteredListener", void.class, Consumer.class);
            MethodHandle addExited = Handles.virtual(apiType[0], "addDungeonExitedListener", void.class, Consumer.class);
            MethodHandle removeExited = Handles.virtual(apiType[0], "removeDungeonExitedListener", void.class, Consumer.class);
            MethodHandle addCompleted = Handles.virtual(apiType[0], "addDungeonCompletedListener", void.class, Consumer.class);
            MethodHandle removeCompleted = Handles.virtual(apiType[0], "removeDungeonCompletedListener", void.class, Consumer.class);
            Consumer<Object> onEntered = e -> guard(F_RUNS, () -> entered(e, entered));
            Consumer<Object> onExited = e -> guard(F_RUNS, () -> exited(e, exited));
            Consumer<Object> onCompleted = e -> guard(F_RUNS, () -> completed(e, completed));
            listen(addEntered, removeEntered, onEntered);
            listen(addExited, removeExited, onExited);
            listen(addCompleted, removeCompleted, onCompleted);
            Subscription deaths = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.ENTITY_DIED), Delivery.SYNC,
                    ev -> guard(F_RUNS, () -> died(ev)));
            onUnbind(deaths::close);
        });
        feature(F_BOSSES, () -> {
            Class<?> event = Handles.type(loader, names.bossKilled());
            MethodHandle bossType = Handles.virtual(event, "bossTypeId", String.class);
            MethodHandle world = Handles.virtual(event, "worldName", String.class);
            MethodHandle dungeon = Handles.virtual(event, "dungeonId", String.class);
            MethodHandle attackers = Handles.virtual(event, "attackerUuids", Set.class);
            MethodHandle add = Handles.virtual(apiType[0], "addBossKilledListener", void.class, Consumer.class);
            MethodHandle remove = Handles.virtual(apiType[0], "removeBossKilledListener", void.class, Consumer.class);
            Consumer<Object> onBoss = e -> guard(F_BOSSES, () -> bossKilled(e, bossType, world, dungeon, attackers));
            listen(add, remove, onBoss);
        });
    }

    @Override
    protected void beforeFactsCleared() {
        runs.clear();
    }

    /** Adds a consumer to one of the API's lists and removes the same instance at unbind. */
    private void listen(MethodHandle add, MethodHandle remove, Consumer<Object> consumer) throws Throwable {
        Object target = api;
        add.invoke(target, consumer);
        onUnbind(() -> {
            try {
                remove.invoke(target, consumer);
            } catch (Throwable ignored) {
                // the mod is gone
            }
        });
    }

    // ---- runs --------------------------------------------------------------------------------------------

    private void entered(Object event, RunReader r) throws Throwable {
        UUID player = uuidOf(r.player().invoke(event));
        String world = Handles.stringOf(r.world().invoke(event));
        if (player == null || world == null) {
            return;
        }
        String dungeon = Handles.stringOf(r.dungeon().invoke(event));
        String mode = modeOf(r.mode().invoke(event));
        RunTable.Entered in = runs.enter(world, dungeon, mode, player, clock.getAsLong());
        if (in.first() && wants(EventKind.DUNGEON_RUN)) {
            publish(new DungeonRun(header(world, player), ID, dungeon, DungeonRun.Phase.ENTERED, mode, in.partySize(), 0L));
        }
    }

    private void completed(Object event, RunReader r) throws Throwable {
        UUID player = uuidOf(r.player().invoke(event));
        String world = Handles.stringOf(r.world().invoke(event));
        if (player == null || world == null) {
            return;
        }
        String dungeon = Handles.stringOf(r.dungeon().invoke(event));
        String mode = modeOf(r.mode().invoke(event));
        RunTable.Outcome out = runs.complete(world, mode, player, clock.getAsLong());
        if (out.partySize() > 0) {
            services.counters.raise(player, ModKeys.key(ID, PARTY_BEST), out.partySize());
        }
        if (wants(EventKind.DUNGEON_RUN)) {
            publish(new DungeonRun(header(world, player), ID, dungeon, DungeonRun.Phase.COMPLETED, out.mode(), out.partySize(),
                    out.durationMillis()));
        }
    }

    private void exited(Object event, RunReader r) throws Throwable {
        UUID player = uuidOf(r.player().invoke(event));
        String world = Handles.stringOf(r.world().invoke(event));
        if (player == null || world == null) {
            return;
        }
        String dungeon = Handles.stringOf(r.dungeon().invoke(event));
        RunTable.Outcome out = runs.exit(world, player, clock.getAsLong());
        if (out.phase() != null && wants(EventKind.DUNGEON_RUN)) {
            publish(new DungeonRun(header(world, player), ID, dungeon, out.phase(), out.mode(), out.partySize(), out.durationMillis()));
        }
    }

    private void died(TrackerEvent event) {
        if (!(event instanceof EntityDied d) || d.victim() == null || !d.victim().isPlayer()) {
            return;
        }
        UUID player = d.victim().uuid();
        String world = d.world() != null ? d.world() : services.players.worldOf(player);
        runs.died(world, player);
    }

    // ---- bosses ------------------------------------------------------------------------------------------

    private void bossKilled(Object event, MethodHandle bossType, MethodHandle world, MethodHandle dungeon, MethodHandle attackers)
            throws Throwable {
        if (!wants(EventKind.MOD_BOSS_DEFEATED)) {
            return;
        }
        String boss = Handles.stringOf(bossType.invoke(event));
        if (boss == null) {
            return;
        }
        Set<UUID> credited = new LinkedHashSet<>();
        if (attackers.invoke(event) instanceof Collection<?> c) {
            for (Object o : c) {
                UUID u = uuidOf(o);
                if (u != null) {
                    credited.add(u);
                }
            }
        }
        if (credited.isEmpty()) {
            return;
        }
        String worldName = Handles.stringOf(world.invoke(event));
        String dungeonId = Handles.stringOf(dungeon.invoke(event));
        List<UUID> participants = List.copyOf(credited);
        for (UUID player : credited) {
            publish(new ModBossDefeated(header(worldName, player), ID, boss, dungeonId, participants));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static String modeOf(Object mode) {
        String s = Handles.stringOf(mode);
        return s == null || s.isBlank() ? DEFAULT_MODE : s;
    }

    /** The API object the binding resolved, for the tests. */
    Object api() {
        return api;
    }

    /** How many dungeon worlds have a run in progress, for the tests. */
    int trackedWorlds() {
        return runs.size();
    }
}
