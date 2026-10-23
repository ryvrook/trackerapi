package dev.ryvrook.trackerapi.mods.lucky;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.LuckyDrop;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The binding to Lucky Mining. The mod has no API and no events. Its one system keeps a public static
 * {@code BreakBlockEventSystem.luckyMiningInfo} map of the streak per player, written on the world thread while
 * it handles the engine's break event, with three private fields per entry, the ore id, the current chance and
 * the time of the last bonus. The binding reads that map on the same world thread from TrackerAPI's own
 * {@code BlockBroken} events delivered sync, and never writes to it. An entry whose {@code lastBreak} moved since
 * the last read, while the same entry existed before, means a bonus ore was placed, which publishes one
 * {@link LuckyDrop} with the streak length derived from the chance.
 *
 * <p>The mod's system and TrackerAPI's run in an order the engine decides, so a bonus may be seen on the player's
 * next break rather than the one that earned it. The last bonus of a streak that then expires is missed when
 * the mod's system runs first and has removed the entry by that next break.
 */
public final class LuckyBinding extends AbstractBinding {
    public static final String ID = "lucky";
    public static final String F_STREAKS = "streak map";
    /** The mod's default start chance and increase per break, which turn a chance into a streak length. */
    static final double START_CHANCE = 0.4;
    static final double STEP_CHANCE = 0.02;

    /** The class names of the mod, with a production default and a test override. */
    public record Names(String system, String info) {
        public static final Names PRODUCTION = new Names("com.buuz135.luckymining.BreakBlockEventSystem",
                "com.buuz135.luckymining.BreakBlockEventSystem$LuckyMiningInfo");
    }

    private final Names names;
    private volatile Map<?, ?> streaks;
    private volatile Field block;
    private volatile Field chance;
    private volatile Field lastBreak;
    /** The last values read per player, so a moved lastBreak on the same entry is a bonus. */
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();

    public LuckyBinding() {
        this(Names.PRODUCTION);
    }

    public LuckyBinding(Names names) {
        super(ID, "Buuz135:LuckyMining", "Lucky Mining");
        this.names = names;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.LUCKY_DROP);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(StatSpec.sum(ModKeys.key(ID, ModKeys.DROPS), "Lucky drops"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.DROPS), "Lucky drops by ore", "Ore"),
                StatSpec.max(ModKeys.key(ID, ModKeys.STREAK_BEST), "Longest streak"));
    }

    @Override
    protected void resolve(Object plugin) throws Throwable {
        feature(F_STREAKS, () -> {
            Class<?> system = Handles.type(loader, names.system());
            Object map = Handles.staticField(system, "luckyMiningInfo");
            if (!(map instanceof Map<?, ?> m)) {
                throw new NoSuchFieldException(names.system() + ".luckyMiningInfo is not a Map");
            }
            Class<?> info = Handles.type(loader, names.info());
            // the plugin's classes are in the unnamed module, so the private fields open to reflection
            Field b = instanceField(info, "block", String.class);
            Field c = instanceField(info, "chance", double.class);
            Field l = instanceField(info, "lastBreak", long.class);
            streaks = m;
            block = b;
            chance = c;
            lastBreak = l;
            onUnbind(() -> {
                streaks = null;
                block = null;
                chance = null;
                lastBreak = null;
            });
            Subscription sub = services.publisher.subscribe("mods/" + ID,
                    Set.of(EventKind.BLOCK_BROKEN, EventKind.PLAYER_LEFT), Delivery.SYNC, this::onEvent);
            onUnbind(sub::close);
        });
    }

    private static Field instanceField(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        if (Modifier.isStatic(f.getModifiers()) || f.getType() != type) {
            throw new NoSuchFieldException(owner.getName() + "." + name + " is not an instance " + type.getSimpleName());
        }
        f.setAccessible(true);
        return f;
    }

    @Override
    protected void beforeFactsCleared() {
        seen.clear();
    }

    // ---- the sync listener, on the world thread inside the engine's break handler ------------------------

    private void onEvent(TrackerEvent event) {
        if (event instanceof BlockBroken b) {
            guard(F_STREAKS, () -> onBreak(b));
        } else if (event instanceof PlayerLeft l) {
            if (l.player() != null) {
                seen.remove(l.player());
            }
        }
    }

    private void onBreak(BlockBroken b) throws IllegalAccessException {
        Map<?, ?> map = streaks;
        Field last = lastBreak;
        UUID player = b.player();
        if (map == null || last == null || player == null) {
            return;
        }
        Object entry = map.get(player);
        if (entry == null) {
            seen.remove(player);
            return;
        }
        long at = last.getLong(entry);
        Seen s = seen.get(player);
        if (s == null || s.entry != entry) {
            // a streak started, its lastBreak is the creating break and not a bonus
            seen.put(player, new Seen(entry, at));
            return;
        }
        if (at == s.lastBreak) {
            return;
        }
        s.lastBreak = at;
        if (!wants(EventKind.LUCKY_DROP)) {
            return;
        }
        String ore = Handles.stringOf(block.get(entry));
        int streak = streakOf(chance.getDouble(entry));
        publish(new LuckyDrop(header(b.world(), player), ID, ore, ore, streak));
    }

    /** The streak length in breaks from the live chance, the creating break included, at least 1. */
    static int streakOf(double chance) {
        int n = (int) Math.round((chance - START_CHANCE) / STEP_CHANCE) + 1;
        return Math.max(1, n);
    }

    /** The entry object and its lastBreak as last read for one player. Touched on that player's world thread. */
    private static final class Seen {
        final Object entry;
        long lastBreak;

        Seen(Object entry, long lastBreak) {
            this.entry = entry;
            this.lastBreak = lastBreak;
        }
    }
}
