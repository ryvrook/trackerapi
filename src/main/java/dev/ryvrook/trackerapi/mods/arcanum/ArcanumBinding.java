package dev.ryvrook.trackerapi.mods.arcanum;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * The binding to Arcanum 0.6.3 ({@code com.zenkuro:Arcanum}), written from its adapter contract. Two features.
 *
 * <p>Casts. The mod's only cast signal is its ECS entity event {@code CadCastOutcomeEvent}, so the binding registers
 * TrackerAPI's own {@link ArcanumCastSystem} for that event class at bind and removes it at unbind. A completed cast
 * reads the player's prepared cast component, still present while the outcome is handled, and publishes
 * {@link ModAbilityUsed} with the spell's name as a key and the CAD's item id, then {@link ModTally} for the energy
 * spent and, when they beat the player's best, the spell's complexity and its rune count. A failed preparation or
 * completion is a {@link ModTally} of {@code failed} by stage. The event carries no spell, so a cast whose component
 * is already gone counts under {@value #UNKNOWN}.
 *
 * <p>Runes. The public knowledge API's {@code learnedPieces(ref, store)} is read on the player's world thread at
 * bind, at join and every {@value #POLL_SECONDS} seconds, at most once per {@value #POLL_SECONDS} seconds per player
 * and at most one waiting read per world. The set is diffed with the last read of the session and every new rune is
 * a {@link ModMilestone} of category {@code rune}, so {@code arcanum:runes} counts the distinct runes known. On the
 * first read of a session runes the counters already hold are not published again. The state of a player is
 * dropped when they leave.
 */
public final class ArcanumBinding extends AbstractBinding {
    public static final String ID = "arcanum";
    public static final String PLUGIN_ID = "com.zenkuro:Arcanum";
    public static final String NAME = "Arcanum";

    static final String F_CASTS = "casts";
    static final String F_RUNES = "runes";

    static final String CAT_RUNE = "rune";
    static final String STAT_ENERGY = "energy";
    static final String STAT_COMPLEXITY = "complexity";
    static final String STAT_SIZE = "size";
    static final String STAT_FAILED = "failed";
    static final String STAGE_PREPARE = "prepare";
    static final String STAGE_CAST = "cast";
    static final String BEST = "_best";

    /** The key of a cast whose prepared component could not be read. */
    static final String UNKNOWN = "unknown";
    /** The key of a spell whose name has no letter or digit. */
    static final String UNNAMED = "unnamed";
    /** The key of a player's spells past {@link #SPELL_KEYS_PER_PLAYER}. */
    static final String OTHER = "other";
    /** Longest spell key. */
    static final int SPELL_KEY_LENGTH = 48;
    /** Distinct spell keys a player may hold, spell names are free text. */
    static final int SPELL_KEYS_PER_PLAYER = 64;

    /** How often every online player's runes are read. */
    static final long POLL_SECONDS = 30;
    /** Shortest gap between two rune reads of one player, less a little slack for a late timer. */
    static final long MIN_GAP_NANOS = TimeUnit.SECONDS.toNanos(POLL_SECONDS) - TimeUnit.MILLISECONDS.toNanos(250);

    private final ArcanumNames names;
    /** When set, the runes feature asks for a method the mod does not have, to test a partial bind. */
    volatile boolean breakRunes;
    /** The clock of the per player read gap, a test moves it. */
    volatile LongSupplier clock = System::nanoTime;

    private volatile Runes runes;
    private final Map<UUID, Set<String>> known = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRead = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> spellKeys = new ConcurrentHashMap<>();

    /** The handles of the cast feature, held by the listener only. */
    private record Cast(MethodHandle stage, MethodHandle outcome, MethodHandle spellName, MethodHandle energyCost,
                        MethodHandle spell, MethodHandle section, MethodHandle slot, MethodHandle nodes,
                        MethodHandle stats, MethodHandle complexity) {
    }

    /** The handles of the rune reads, dropped at unbind. */
    private record Runes(MethodHandle api, MethodHandle knowledge, MethodHandle learned) {
    }

    public ArcanumBinding() {
        this(ArcanumNames.LIVE);
    }

    public ArcanumBinding(ArcanumNames names) {
        super(ID, PLUGIN_ID, NAME);
        this.names = names;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_ABILITY_USED, EventKind.MOD_TALLY, EventKind.MOD_MILESTONE);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(ModKeys.key(ID, ModKeys.ABILITIES), "Spells cast"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.ABILITIES), "Casts per spell", "Spell"),
                StatSpec.sum(ModKeys.key(ID, STAT_ENERGY), "Energy spent"),
                StatSpec.max(ModKeys.key(ID, STAT_ENERGY + BEST), "Most expensive spell"),
                StatSpec.max(ModKeys.key(ID, STAT_COMPLEXITY + BEST), "Most complex spell"),
                StatSpec.max(ModKeys.key(ID, STAT_SIZE + BEST), "Largest spell"),
                StatSpec.sum(ModKeys.key(ID, STAT_FAILED), "Failed casts"),
                StatSpec.perId(ModKeys.key(ID, STAT_FAILED), "Failed casts by stage", "Stage"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_RUNE)), "Runes known"),
                StatSpec.discovery(ModKeys.key(ID, CAT_RUNE), "Runes", "Rune"));
    }

    // ---- resolve -----------------------------------------------------------------------------------------

    @Override
    protected void resolve(Object plugin) throws Throwable {
        feature(F_CASTS, () -> {
            Class<?> event = type(".cad.casting.CadCastOutcomeEvent");
            Class<?> stage = type(".cad.casting.CadCastOutcomeEvent$Stage");
            Class<?> outcome = type(".cad.casting.CadCastOutcomeEvent$Outcome");
            Class<?> prepared = type(".cad.casting.PreparedCadCastComponent");
            Class<?> spell = type(".spell.compile.CompiledSpell");
            Class<?> stats = type(".spell.compile.CompiledSpellStats");
            Cast cast = new Cast(Handles.virtual(event, "stage", stage),
                    Handles.virtual(event, "outcome", outcome),
                    Handles.virtual(prepared, "spellName", String.class),
                    Handles.virtual(prepared, "energyCost", int.class),
                    Handles.virtual(prepared, "spell", spell),
                    Handles.virtual(prepared, "inventorySectionId", int.class),
                    Handles.virtual(prepared, "inventorySlot", short.class),
                    Handles.virtual(spell, "nodes", Map.class),
                    Handles.virtual(spell, "stats", stats),
                    Handles.virtual(stats, "complexity", int.class));
            Object preparedType = Handles.statikByName(prepared, "getComponentType", 0).invoke();
            ArcanumEngine.CastListener listener = (e, uuid, component, slots) ->
                    guard(F_CASTS, () -> onCast(cast, e, uuid, component, slots));
            // Last, so a member that did not resolve leaves no system behind.
            onUnbind(names.engine().listen(event, preparedType, listener));
        });

        feature(F_RUNES, () -> {
            Class<?> api = type(".api.spell.ArcanumApi");
            Class<?> knowledge = type(".api.knowledge.SpellKnowledge");
            runes = new Runes(Handles.statik(api, "get", api),
                    Handles.virtual(api, "knowledge", knowledge),
                    Handles.virtual(knowledge, breakRunes ? "learnedRunes" : "learnedPieces", Set.class, Ref.class, ComponentAccessor.class));
            onUnbind(() -> runes = null);
            Subscription players = services.publisher.subscribe("mods/" + ID,
                    Set.of(EventKind.PLAYER_JOINED, EventKind.PLAYER_LEFT), Delivery.ASYNC, e -> {
                        if (e instanceof PlayerLeft) {
                            forget(e.player());
                        } else if (e.player() != null && has(F_RUNES)) {
                            names.engine().read(e.player(), this::runeRead);
                        }
                    });
            onUnbind(players::close);
            ScheduledFuture<?> poll = services.timer.scheduleAtFixedRate(this::pollSafely, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            onUnbind(() -> poll.cancel(false));
        });
        if (has(F_RUNES)) {
            names.engine().readAll(this::runeRead);
        }
    }

    private Class<?> type(String suffix) throws ClassNotFoundException {
        return Handles.type(loader, names.mod() + suffix);
    }

    @Override
    protected void reset() {
        known.clear();
        lastRead.clear();
        spellKeys.clear();
    }

    private void forget(UUID uuid) {
        if (uuid != null) {
            known.remove(uuid);
            lastRead.remove(uuid);
            spellKeys.remove(uuid);
        }
    }

    // ---- casts -------------------------------------------------------------------------------------------

    private void onCast(Cast c, Object event, UUID uuid, Object prepared, ArcanumEngine.Slots slots) throws Throwable {
        if (uuid == null) {
            return;
        }
        String stage = Handles.nameOf(c.stage().invoke(event));
        String outcome = Handles.nameOf(c.outcome().invoke(event));
        if ("FAILURE".equals(outcome)) {
            if (wants(EventKind.MOD_TALLY)) {
                String id = "PREPARATION".equals(stage) ? STAGE_PREPARE : STAGE_CAST;
                publish(new ModTally(header(uuid), ID, STAT_FAILED, id, 1, 0));
            }
            return;
        }
        if (!"SUCCESS".equals(outcome) || !"COMPLETION".equals(stage)) {
            return;
        }
        boolean wantCast = wants(EventKind.MOD_ABILITY_USED);
        boolean wantTally = wants(EventKind.MOD_TALLY);
        if (!wantCast && !wantTally) {
            return;
        }
        String key = UNKNOWN;
        String item = null;
        int energy = 0;
        int complexity = 0;
        int size = 0;
        if (prepared != null) {
            key = spellKey(uuid, Handles.stringOf(c.spellName().invoke(prepared)));
            energy = Handles.intOf(c.energyCost().invoke(prepared), 0);
            Object spell = c.spell().invoke(prepared);
            if (spell != null) {
                if (c.nodes().invoke(spell) instanceof Map<?, ?> nodes) {
                    size = nodes.size();
                }
                Object stats = c.stats().invoke(spell);
                complexity = stats == null ? 0 : Handles.intOf(c.complexity().invoke(stats), 0);
            }
            if (wantCast) {
                item = slots == null ? null : slots.itemAt(Handles.intOf(c.section().invoke(prepared), -1),
                        Handles.intOf(c.slot().invoke(prepared), -1));
            }
        }
        if (wantCast) {
            publish(new ModAbilityUsed(header(uuid), ID, key, item, null));
        }
        if (wantTally) {
            if (energy > 0) {
                publish(new ModTally(header(uuid), ID, STAT_ENERGY, null, energy, energy));
            }
            // The bests are journaled only when they move.
            if (complexity > best(uuid, STAT_COMPLEXITY)) {
                publish(new ModTally(header(uuid), ID, STAT_COMPLEXITY, null, 0, complexity));
            }
            if (size > best(uuid, STAT_SIZE)) {
                publish(new ModTally(header(uuid), ID, STAT_SIZE, null, 0, size));
            }
        }
    }

    private long best(UUID uuid, String stat) {
        return services.counters.get(uuid, ModKeys.key(ID, stat + BEST));
    }

    /**
     * The counter key of a spell name for this player. The player's distinct keys are capped, the ones they already
     * hold in their counters are read once per session.
     */
    private String spellKey(UUID uuid, String name) {
        String key = normalise(name);
        if (!online(uuid)) {
            return key;
        }
        Set<String> keys = spellKeys.computeIfAbsent(uuid, this::heldSpellKeys);
        if (keys.contains(key)) {
            return key;
        }
        synchronized (keys) {
            if (keys.contains(key)) {
                return key;
            }
            if (keys.size() >= SPELL_KEYS_PER_PLAYER) {
                return OTHER;
            }
            keys.add(key);
            return key;
        }
    }

    private Set<String> heldSpellKeys(UUID uuid) {
        Set<String> keys = ConcurrentHashMap.newKeySet();
        String prefix = ModKeys.key(ID, ModKeys.ABILITIES) + ':';
        for (String k : services.counters.all(uuid).keySet()) {
            if (k.startsWith(prefix)) {
                String id = k.substring(prefix.length());
                if (!id.equals(OTHER) && !id.equals(UNKNOWN)) {
                    keys.add(id);
                }
            }
        }
        return keys;
    }

    /**
     * A spell name as a key: lower case, ASCII letters and digits kept, every other run turned into one underscore,
     * no underscore at either end, at most {@value #SPELL_KEY_LENGTH} characters, {@value #UNNAMED} when nothing is
     * left.
     */
    static String normalise(String name) {
        if (name == null) {
            return UNNAMED;
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), SPELL_KEY_LENGTH));
        boolean gap = false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length() && sb.length() < SPELL_KEY_LENGTH; i++) {
            char ch = lower.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                if (gap && sb.length() > 0) {
                    sb.append('_');
                    if (sb.length() >= SPELL_KEY_LENGTH) {
                        break;
                    }
                }
                sb.append(ch);
                gap = false;
            } else {
                gap = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') {
            end--;
        }
        return end == 0 ? UNNAMED : sb.substring(0, end);
    }

    // ---- runes -------------------------------------------------------------------------------------------

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException e) {
            if (log != null) {
                log.at(Level.FINE).withCause(e).log("TrackerAPI could not poll Arcanum");
            }
        }
    }

    /** One poll round, every online player's runes. Timer thread. */
    void poll() {
        if (has(F_RUNES)) {
            names.engine().readAll(this::runeRead);
        }
    }

    /** The world thread read. */
    private void runeRead(UUID uuid, Object ref, Object store) {
        guard(F_RUNES, () -> readRunes(uuid, ref, store));
    }

    private void readRunes(UUID uuid, Object ref, Object store) throws Throwable {
        Runes r = runes;
        if (r == null || uuid == null || !online(uuid) || !due(uuid)) {
            return;
        }
        Object api = r.api().invoke();
        Object knowledge = api == null ? null : r.knowledge().invoke(api);
        if (knowledge == null || !(r.learned().invoke(knowledge, ref, store) instanceof Set<?> set)) {
            return;
        }
        Set<String> now = new HashSet<>();
        for (Object o : set) {
            String id = runeId(Handles.stringOf(o));
            if (id != null) {
                now.add(id);
            }
        }
        Set<String> before = known.put(uuid, now);
        fact(uuid, "runes", String.valueOf(now.size()));
        if (!wants(EventKind.MOD_MILESTONE)) {
            return;
        }
        for (String id : now) {
            if (before != null ? before.contains(id)
                    : services.counters.get(uuid, ModKeys.key(ID, CAT_RUNE, id)) > 0) {
                continue;
            }
            publish(new ModMilestone(header(uuid), ID, CAT_RUNE, id, null));
        }
    }

    /** True when the player was not read in the last {@value #POLL_SECONDS} seconds, and marks the read. */
    private boolean due(UUID uuid) {
        long now = clock.getAsLong();
        boolean[] due = {false};
        lastRead.compute(uuid, (u, last) -> {
            if (last == null || now - last >= MIN_GAP_NANOS) {
                due[0] = true;
                return now;
            }
            return last;
        });
        return due[0];
    }

    /**
     * A piece id as a counter id. The mod's own namespace is dropped ({@code arcanum:damage} is {@code damage}), any
     * other colon becomes an underscore so the id stays one key segment.
     */
    static String runeId(String pieceId) {
        if (pieceId == null || pieceId.isBlank()) {
            return null;
        }
        String id = pieceId.startsWith(ID + ':') ? pieceId.substring(ID.length() + 1) : pieceId;
        id = id.replace(':', '_');
        return id.isEmpty() ? null : id;
    }
}
