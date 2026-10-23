package dev.ryvrook.trackerapi.mods.zephyr;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reads Zephyr 0.4.2 by narwhals. The movement events ride the engine bus, synchronously on the world thread
 * inside an interaction or the damage pipeline, and each becomes a {@link ModAction}: {@code dodge},
 * {@code perfect_dodge} (pvp and target type from the attacker), {@code double_jump}, {@code wall_jump},
 * {@code grapple} and {@code vanish}. Zipline rides have no event, so a poll every ten seconds reads each online
 * player's ride count on the world thread and publishes {@code zipline} for the rides since the last read.
 *
 * <p>Every class is loaded through the mod's loader. Perfect Parries by the same author shares the root package and
 * repeats class names, so nothing here is ever shared with that binding.
 */
public final class ZephyrBinding extends AbstractBinding {
    public static final String ID = "zephyr";
    static final String F_CORE = "core";
    static final String F_DODGE = "dodge";
    static final String F_JUMPS = "jumps";
    static final String F_GRAPPLE = "grapple";
    static final String F_VANISH = "vanish";
    static final String F_ZIPLINE = "zipline";
    static final String A_DODGE = "dodge";
    static final String A_PERFECT_DODGE = "perfect_dodge";
    static final String A_DOUBLE_JUMP = "double_jump";
    static final String A_WALL_JUMP = "wall_jump";
    static final String A_GRAPPLE = "grapple";
    static final String A_VANISH = "vanish";
    static final String A_ZIPLINE = "zipline";
    /** Seconds between two reads of the zipline ride counts. */
    static final long ZIPLINE_SECONDS = 10;
    /** The most rides one read publishes, more than that in ten seconds is not a player riding. */
    static final int ZIPLINE_BURST = 20;

    /** The class names of the mod's API, the live ones by default, the fakes in tests. */
    public record Names(String api, String plugin, String perfectDodgeEvent, String dodgeStartEvent,
                        String doubleJumpEvent, String wallJumpEvent, String grappleStartEvent,
                        String vanishStartEvent, String ziplineComponent) {
        public static final Names LIVE = new Names("org.narwhals.plugin.api.ZephyrAPI",
                "org.narwhals.plugin.Zephyr",
                "org.narwhals.plugin.api.events.PerfectDodgeEvent",
                "org.narwhals.plugin.api.events.dodge.DodgeIFrameStartEvent",
                "org.narwhals.plugin.api.events.doublejump.DoubleJumpEvent",
                "org.narwhals.plugin.api.events.walljump.WallJumpEvent",
                "org.narwhals.plugin.api.events.grapple.GrappleStartEvent",
                "org.narwhals.plugin.api.events.vanish.VanishStartEvent",
                "org.narwhals.plugin.items.utility.zipline.ZiplineRideComponent");
    }

    private final Names names;
    private final Engine engine;
    /** The last ride count read per online player, the baseline for the next delta. */
    private final Map<UUID, Integer> lastRides = new ConcurrentHashMap<>();
    private volatile Object ziplineType;
    private volatile MethodHandle ziplineCount;

    public ZephyrBinding() {
        this(Names.LIVE, null);
    }

    ZephyrBinding(Names names, Engine engine) {
        super(ID, "narwhals:Zephyr", "Zephyr");
        this.names = names;
        this.engine = engine != null ? engine : new LiveEngine(this::engineEvent);
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_ACTION);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(StatSpec.sum(ModKeys.key(ID, A_DODGE), "Dodges"),
                StatSpec.sum(ModKeys.key(ID, A_PERFECT_DODGE), "Perfect dodges"),
                StatSpec.sum(ModKeys.key(ID, A_PERFECT_DODGE, ModKeys.PVP), "PvP perfect dodges"),
                StatSpec.sum(ModKeys.key(ID, A_DOUBLE_JUMP), "Double jumps"),
                StatSpec.sum(ModKeys.key(ID, A_WALL_JUMP), "Wall jumps"),
                StatSpec.sum(ModKeys.key(ID, A_GRAPPLE), "Grapples"),
                StatSpec.sum(ModKeys.key(ID, A_VANISH), "Vanishes"),
                StatSpec.sum(ModKeys.key(ID, A_ZIPLINE), "Zipline rides"));
    }

    @Override
    protected void resolve(Object plugin) {
        feature(F_CORE, () -> {
            Class<?> api = Handles.type(loader, names.api());
            MethodHandle get = Handles.statik(api, "get", api);
            get.invoke(); // null between the mod's shutdown and its next setup, which is fine
        });
        feature(F_DODGE, () -> {
            move(F_DODGE, names.dodgeStartEvent(), A_DODGE);
            Class<?> event = Handles.type(loader, names.perfectDodgeEvent());
            MethodHandle defender = Handles.virtual(event, "getDefender", Ref.class);
            MethodHandle attacker = Handles.virtual(event, "getAttacker", Ref.class);
            listen(event, e -> guard(F_DODGE, () -> onPerfectDodge(e, defender, attacker)));
        });
        feature(F_JUMPS, () -> {
            move(F_JUMPS, names.doubleJumpEvent(), A_DOUBLE_JUMP);
            move(F_JUMPS, names.wallJumpEvent(), A_WALL_JUMP);
        });
        feature(F_GRAPPLE, () -> move(F_GRAPPLE, names.grappleStartEvent(), A_GRAPPLE));
        feature(F_VANISH, () -> move(F_VANISH, names.vanishStartEvent(), A_VANISH));
        feature(F_ZIPLINE, () -> {
            Class<?> main = Handles.type(loader, names.plugin());
            Class<?> component = Handles.type(loader, names.ziplineComponent());
            MethodHandle type = Handles.statik(main, "getZiplineRideComponentType", ComponentType.class);
            MethodHandle count = MethodHandles.publicLookup().findGetter(component, "ziplineRideCount", int.class);
            Object resolved = type.invoke();
            if (resolved == null) {
                throw new IllegalStateException("the zipline component type is not set yet");
            }
            ziplineType = resolved;
            ziplineCount = count.asType(count.type().generic());
            ScheduledFuture<?> poll = services.timer.scheduleAtFixedRate(() -> guard(F_ZIPLINE, this::pollZiplines),
                    ZIPLINE_SECONDS, ZIPLINE_SECONDS, TimeUnit.SECONDS);
            onUnbind(() -> {
                poll.cancel(false);
                ziplineType = null;
                ziplineCount = null;
                lastRides.clear();
            });
        });
    }

    /** One event class with a {@code getPlayer()} ref becoming one action. */
    private void move(String feature, String className, String action) throws Throwable {
        Class<?> event = Handles.type(loader, className);
        MethodHandle player = Handles.virtual(event, "getPlayer", Ref.class);
        listen(event, e -> guard(feature, () -> onMove(e, player, action)));
    }

    private void listen(Class<?> event, Consumer<Object> consumer) {
        Runnable undo = engine.listen(event, consumer);
        if (undo != null) {
            onUnbind(undo);
        }
    }

    // ---- callbacks, world thread -------------------------------------------------------------------------

    private void onMove(Object event, MethodHandle player, String action) throws Throwable {
        if (!wants(EventKind.MOD_ACTION)) {
            return;
        }
        Object ref = player.invoke(event);
        Actor who = engine.actor(ref, ref);
        if (!who.isPlayer()) {
            return;
        }
        publish(new ModAction(header(who.uuid()), ID, action, false, null, 0));
    }

    private void onPerfectDodge(Object event, MethodHandle defender, MethodHandle attacker) throws Throwable {
        if (!wants(EventKind.MOD_ACTION)) {
            return;
        }
        Object defenderRef = defender.invoke(event);
        Actor who = engine.actor(defenderRef, defenderRef);
        if (!who.isPlayer()) {
            return;
        }
        Actor against = engine.actor(attacker.invoke(event), defenderRef);
        String type = against.isCreature() ? against.typeId() : null;
        publish(new ModAction(header(who.uuid()), ID, A_PERFECT_DODGE, against.isPlayer(), type, 0));
    }

    // ---- zipline poll ------------------------------------------------------------------------------------

    /** Timer thread. Drops players who left, then asks the engine to read every online player's component. */
    void pollZiplines() {
        Object type = ziplineType;
        MethodHandle count = ziplineCount;
        if (type == null || count == null) {
            return;
        }
        lastRides.keySet().removeIf(uuid -> !online(uuid));
        engine.eachPlayer(type, (uuid, component) ->
                guard(F_ZIPLINE, () -> onRides(uuid, Handles.intOf(count.invoke(component), -1))));
    }

    /**
     * World thread. The first read of a player is the baseline. A count below the last one is the reset on a
     * new join, so everything since then counts.
     */
    void onRides(UUID uuid, int rides) {
        if (uuid == null || rides < 0) {
            return;
        }
        Integer last = lastRides.put(uuid, rides);
        if (last == null) {
            return;
        }
        int delta = rides < last ? rides : rides - last;
        if (delta <= 0 || !wants(EventKind.MOD_ACTION)) {
            return;
        }
        for (int i = 0; i < Math.min(delta, ZIPLINE_BURST); i++) {
            publish(new ModAction(header(uuid), ID, A_ZIPLINE, false, null, 0));
        }
    }
}
