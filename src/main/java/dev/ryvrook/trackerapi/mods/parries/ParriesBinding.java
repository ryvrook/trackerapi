package dev.ryvrook.trackerapi.mods.parries;

import com.hypixel.hytale.component.Ref;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reads Perfect Parries 0.9.7 by narwhals. The mod fires two events on the engine bus, synchronously on the world
 * thread inside the damage pipeline: a successful parry and a counterattack that landed. Both become
 * {@link ModAction}s, {@code parry} and {@code counterattack}, when the defender or attacker is a player. The
 * parry event also fires when an NPC parries a player, those are left out. A per player streak of parries, reset
 * by a death, feeds {@code parries:streak_best}.
 *
 * <p>Every class is loaded through the mod's loader. Zephyr by the same author shares the root package and repeats
 * class names, so nothing here is ever shared with that binding.
 */
public final class ParriesBinding extends AbstractBinding {
    public static final String ID = "parries";
    static final String F_CORE = "core";
    static final String F_PARRY = "parry";
    static final String F_COUNTER = "counterattack";
    static final String A_PARRY = "parry";
    static final String A_COUNTER = "counterattack";

    /** The class names of the mod's API, the live ones by default, the fakes in tests. */
    public record Names(String api, String parryEvent, String counterattackEvent) {
        public static final Names LIVE = new Names("org.narwhals.plugin.api.PerfectParriesAPI",
                "org.narwhals.plugin.api.events.PerfectParryEvent",
                "org.narwhals.plugin.api.events.PerfectCounterattackEvent");
    }

    private final Names names;
    private final Engine engine;
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();

    public ParriesBinding() {
        this(Names.LIVE, null);
    }

    ParriesBinding(Names names, Engine engine) {
        super(ID, "narwhals:Perfect Parries", "Perfect Parries");
        this.names = names;
        this.engine = engine != null ? engine : new LiveEngine(this::engineEvent);
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_ACTION);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(StatSpec.sum(ModKeys.key(ID, A_PARRY), "Parries"),
                StatSpec.sum(ModKeys.key(ID, A_PARRY, ModKeys.PVP), "PvP parries"),
                StatSpec.sum(ModKeys.key(ID, A_COUNTER), "Counterattacks"),
                StatSpec.sum(ModKeys.key(ID, A_COUNTER, ModKeys.PVP), "PvP counterattacks"),
                StatSpec.max(ModKeys.key(ID, ModKeys.STREAK_BEST), "Best parry streak"));
    }

    @Override
    protected void resolve(Object plugin) {
        feature(F_CORE, () -> {
            Class<?> api = Handles.type(loader, names.api());
            MethodHandle get = Handles.statik(api, "get", api);
            get.invoke(); // null between the mod's shutdown and its next setup, which is fine
        });
        feature(F_PARRY, () -> {
            Class<?> event = Handles.type(loader, names.parryEvent());
            MethodHandle defender = Handles.virtual(event, "getDefender", Ref.class);
            MethodHandle attacker = Handles.virtual(event, "getAttacker", Ref.class);
            listen(event, e -> guard(F_PARRY, () -> onParry(e, defender, attacker)));
            Subscription resets = services.publisher.subscribe("mods/" + ID,
                    Set.of(EventKind.ENTITY_DIED, EventKind.PLAYER_LEFT), Delivery.SYNC, this::onReset);
            onUnbind(resets::close);
        });
        feature(F_COUNTER, () -> {
            Class<?> event = Handles.type(loader, names.counterattackEvent());
            MethodHandle attacker = Handles.virtual(event, "getAttacker", Ref.class);
            MethodHandle target = Handles.virtual(event, "getTarget", Ref.class);
            MethodHandle multiplier = Handles.virtual(event, "getDamageMultiplier", float.class);
            listen(event, e -> guard(F_COUNTER, () -> onCounterattack(e, attacker, target, multiplier)));
        });
    }

    @Override
    protected void reset() {
        streaks.clear();
    }

    private void listen(Class<?> event, Consumer<Object> consumer) {
        Runnable undo = engine.listen(event, consumer);
        if (undo != null) {
            onUnbind(undo);
        }
    }

    // ---- callbacks, world thread -------------------------------------------------------------------------

    private void onParry(Object event, MethodHandle defender, MethodHandle attacker) throws Throwable {
        if (!wants(EventKind.MOD_ACTION)) {
            return;
        }
        Object defenderRef = defender.invoke(event);
        Actor who = engine.actor(defenderRef, defenderRef);
        if (!who.isPlayer()) {
            return; // an NPC parried a player, the mod fires the same event for those
        }
        Actor against = engine.actor(attacker.invoke(event), defenderRef);
        UUID uuid = who.uuid();
        publish(new ModAction(header(uuid), ID, A_PARRY, against.isPlayer(), typeOf(against), 0));
        int streak = streaks.merge(uuid, 1, Integer::sum);
        services.counters.raise(uuid, ModKeys.key(ID, ModKeys.STREAK_BEST), streak);
    }

    private void onCounterattack(Object event, MethodHandle attacker, MethodHandle target, MethodHandle multiplier)
            throws Throwable {
        if (!wants(EventKind.MOD_ACTION)) {
            return;
        }
        Object attackerRef = attacker.invoke(event);
        Actor who = engine.actor(attackerRef, attackerRef);
        if (!who.isPlayer()) {
            return;
        }
        Actor against = engine.actor(target.invoke(event), attackerRef);
        double value = Handles.doubleOf(multiplier.invoke(event), 0);
        publish(new ModAction(header(who.uuid()), ID, A_COUNTER, against.isPlayer(), typeOf(against), value));
    }

    /** The NPC type of a creature, null for a player or the world. */
    private static String typeOf(Actor actor) {
        return actor.isCreature() ? actor.typeId() : null;
    }

    /** A death ends the victim's streak, a leave drops the entry. TrackerAPI's own events, on their thread. */
    private void onReset(TrackerEvent event) {
        if (event instanceof EntityDied died) {
            Actor victim = died.victim();
            if (victim != null && victim.isPlayer()) {
                streaks.remove(victim.uuid());
            }
        } else if (event instanceof PlayerLeft left) {
            streaks.remove(left.player());
        }
    }
}
