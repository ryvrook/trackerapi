package dev.ryvrook.trackerapi.mods.relics;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.mods.AbstractBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Wan's Wonder Weapons. The mod has no API and fires no events, so this binding reads nothing of the mod beyond
 * its presence and works from the engine and from TrackerAPI's own events. A kill by a player holding a relic
 * counts under {@code relics:kills:<relic>}. A poll once a second looks at the interaction chains of every player
 * holding a relic and publishes {@link ModAbilityUsed} for each new chain whose root is a signature ability, and
 * {@link ModAction} {@code form_switch} for the Eclipsebound and Chromatic Cleaver switch roots.
 *
 * <p>The engine reads sit behind {@link Engine}, {@link EngineReads} in production and a fake in tests.
 */
public final class RelicsBinding extends AbstractBinding {
    public static final String ID = "relics";
    public static final String PLUGIN_ID = "wanmine:WansWonderWeapon";
    public static final String NAME = "Wan's Wonder Weapons";
    public static final String F_KILLS = "kills per relic";
    public static final String F_ABILITIES = "signature abilities";
    public static final String FORM_SWITCH = "form_switch";
    static final String KILLS_KEY = ModKeys.key(ID, "kills");
    static final String ABILITIES_KEY = ModKeys.key(ID, ModKeys.ABILITIES);
    static final String FORM_SWITCH_KEY = ModKeys.key(ID, FORM_SWITCH);
    /** Live chains remembered per player at most, a player never has anywhere near this many at once. */
    static final int SEEN_LIMIT = 64;

    /** One interaction chain of a player, its id and the id of its root interaction. */
    public record Chain(int id, String rootId) {
    }

    /** Receives one relic holder's live chains, on that player's world thread. */
    @FunctionalInterface
    public interface ChainVisitor {
        void visit(UUID player, String world, String heldItem, List<Chain> chains);
    }

    /** The engine reads of this binding. The production one is {@link EngineReads}, tests give a fake. */
    public interface Engine {
        /**
         * The item id in the hand of an online player, or null when the player is gone, holds nothing or the call
         * is not on the player's world thread.
         */
        String heldItem(UUID player);

        /** Throws when the engine lacks what the chain scan needs. Called once at bind. */
        void checkChains() throws Throwable;

        /**
         * Posts a scan to every world with players and returns at once. On each world thread the visitor is called
         * once per player holding a relic, with the player's live chains.
         */
        void scanChains(ChainVisitor visitor);
    }

    private final Engine engine;
    private final long pollMillis;
    private final Map<UUID, Set<Integer>> seen = new ConcurrentHashMap<>();

    /** The production binding, polling once a second. */
    public RelicsBinding() {
        this(new EngineReads(), 1000);
    }

    /** A binding over the given engine reads. A {@code pollMillis} of zero or less runs no timer, tests call {@link #scan} themselves. */
    public RelicsBinding(Engine engine, long pollMillis) {
        super(ID, PLUGIN_ID, NAME);
        this.engine = engine;
        this.pollMillis = pollMillis;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_ABILITY_USED, EventKind.MOD_ACTION);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(KILLS_KEY, "Relic kills"),
                StatSpec.perId(KILLS_KEY, "Kills by relic", "Relic"),
                StatSpec.sum(ABILITIES_KEY, "Signature abilities"),
                StatSpec.perId(ABILITIES_KEY, "Abilities by name", "Ability"),
                StatSpec.sum(FORM_SWITCH_KEY, "Form switches"));
    }

    @Override
    protected void resolve(Object plugin) {
        feature(F_KILLS, () -> {
            Subscription sub = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.ENTITY_DIED), Delivery.SYNC,
                    ev -> guard(F_KILLS, () -> onDied((EntityDied) ev)));
            onUnbind(sub::close);
        });
        feature(F_ABILITIES, () -> {
            engine.checkChains();
            seen.clear();
            if (pollMillis > 0) {
                ScheduledFuture<?> poll = services.timer.scheduleAtFixedRate(() -> guard(F_ABILITIES, this::scan),
                        pollMillis, pollMillis, TimeUnit.MILLISECONDS);
                onUnbind(() -> poll.cancel(false));
            }
            onUnbind(seen::clear);
        });
    }

    @Override
    protected void reset() {
        seen.clear();
    }

    // ---- kills -------------------------------------------------------------------------------------------

    /** The world thread of the death. Reads the killer's hand there and counts the kill under the relic. */
    private void onDied(EntityDied e) {
        if (e.killer() == null || !e.killer().isPlayer()) {
            return;
        }
        UUID killer = e.killer().playerUuid();
        if (killer == null || (e.victim() != null && killer.equals(e.victim().uuid()))) {
            return;
        }
        String relic = Relics.relicOf(engine.heldItem(killer));
        if (relic == null) {
            return;
        }
        services.counters.addId(killer, KILLS_KEY, relic, KILLS_KEY, 1, e.world());
    }

    // ---- abilities ---------------------------------------------------------------------------------------

    /** One round of the poll, the timer thread. Drops players who left and posts the scan to the worlds. */
    void scan() {
        if (!has(F_ABILITIES)) {
            return;
        }
        seen.keySet().removeIf(u -> !online(u));
        engine.scanChains((player, world, held, chains) -> guard(F_ABILITIES, () -> visit(player, world, held, chains)));
    }

    /**
     * A relic holder's live chains, their world thread. A chain not seen last round is a new use of its root. The
     * chains are compared against last round's set in place, and a new set is built only when a chain id came or
     * went, so a player holding a relic with the same chains costs no allocation per second.
     */
    private void visit(UUID player, String world, String held, List<Chain> chains) {
        if (player == null || chains == null) {
            return;
        }
        String relic = Relics.relicOf(held);
        if (relic == null) {
            seen.remove(player);
            return;
        }
        Set<Integer> before = seen.get(player);
        int live = 0;
        boolean changed = false;
        for (int i = 0, n = chains.size(); i < n; i++) {
            Chain c = chains.get(i);
            if (c == null) {
                continue;
            }
            live++;
            if (before != null && before.contains(c.id())) {
                continue;
            }
            changed = true;
            String root = c.rootId();
            Relics.Signature sig = Relics.signatureOf(root);
            if (sig != null) {
                if (wants(EventKind.MOD_ABILITY_USED)) {
                    publish(new ModAbilityUsed(header(world, player), ID, sig.ability(), relic, null));
                }
            } else if (Relics.isFormSwitch(root) && wants(EventKind.MOD_ACTION)) {
                publish(new ModAction(header(world, player), ID, FORM_SWITCH, false, null, 0));
            }
        }
        if (!changed && before != null && before.size() == Math.min(live, SEEN_LIMIT)) {
            return; // the same chains as last round
        }
        if (live == 0) {
            seen.remove(player);
            return;
        }
        Set<Integer> now = new HashSet<>(Math.max(4, Math.min(live, SEEN_LIMIT) * 2));
        for (int i = 0, n = chains.size(); i < n && now.size() < SEEN_LIMIT; i++) {
            Chain c = chains.get(i);
            if (c != null) {
                now.add(c.id());
            }
        }
        seen.put(player, now);
    }

    /** How many players have remembered chains, for tests. */
    int remembered() {
        return seen.size();
    }

    /** The remembered chain ids of a player, the live set and not a copy, for tests. */
    Set<Integer> remembered(UUID player) {
        return seen.get(player);
    }
}
