package dev.ryvrook.trackerapi.mods.vein;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.api.event.VeinMined;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The binding to Vein Mining. The mod has no API and no events. It breaks the extra blocks of a vein through the
 * engine's own break event with its public static {@code MiningManager.IS_VEIN_MINING} thread local set to true,
 * on the world thread, so TrackerAPI's own {@code BlockBroken} events delivered sync (inside the engine's break
 * handler, on that same thread) are where the flag is readable. Flagged breaks of one player closer than
 * {@link #GAP_MILLIS} apart form one vein, closed by the next flagged break after the gap, by the once a second
 * tick on the timer thread finding it idle, or by the player leaving. Closing publishes one {@link VeinMined}.
 *
 * <p>The origin block the player hit is never flagged. The mod cancels its engine break and removes it itself, so
 * a vein's count here is the flagged blocks only, without the origin.
 */
public final class VeinBinding extends AbstractBinding {
    public static final String ID = "vein";
    public static final String F_FLAG = "vein flag";
    /** A gap this long or longer between two flagged breaks of one player closes the vein. */
    public static final long GAP_MILLIS = 250;
    public static final String ORE_BLOCKS = "ore_blocks";

    /** The class names of the mod, with a production default and a test override. */
    public record Names(String miningManager) {
        public static final Names PRODUCTION = new Names("com.einent.veinmining.systems.MiningManager");
    }

    private final Names names;
    private volatile ThreadLocal<?> flag;
    private final Map<UUID, Vein> veins = new ConcurrentHashMap<>();
    /** The clock, replaceable by a test. */
    volatile LongSupplier clock = System::currentTimeMillis;

    public VeinBinding() {
        this(Names.PRODUCTION);
    }

    public VeinBinding(Names names) {
        super(ID, "EineNT:VeinMining", "Vein Mining");
        this.names = names;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.VEIN_MINED);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(StatSpec.sum(ModKeys.key(ID, ModKeys.VEINS), "Veins"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.BLOCKS), "Vein blocks"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.BLOCKS), "Vein blocks by block", "Block"),
                StatSpec.max(ModKeys.key(ID, ModKeys.LARGEST), "Largest vein"),
                StatSpec.sum(ModKeys.key(ID, ORE_BLOCKS), "Ore blocks vein mined"));
    }

    @Override
    protected void resolve(Object plugin) throws Throwable {
        feature(F_FLAG, () -> {
            Class<?> manager = Handles.type(loader, names.miningManager());
            Object value = Handles.staticField(manager, "IS_VEIN_MINING");
            if (!(value instanceof ThreadLocal<?> tl)) {
                throw new NoSuchFieldException(names.miningManager() + ".IS_VEIN_MINING is not a ThreadLocal");
            }
            // the thread local is re-resolved on every bind, a reload makes a new one in the new loader
            flag = tl;
            onUnbind(() -> flag = null);
            Subscription sub = services.publisher.subscribe("mods/" + ID,
                    Set.of(EventKind.BLOCK_BROKEN, EventKind.PLAYER_LEFT), Delivery.SYNC, this::onEvent);
            onUnbind(sub::close);
            ScheduledFuture<?> tick = services.timer.scheduleAtFixedRate(this::tickSafely, 1, 1, TimeUnit.SECONDS);
            onUnbind(() -> tick.cancel(false));
        });
    }

    @Override
    protected void reset() {
        veins.clear();
    }

    // ---- the sync listener, on the world thread inside the engine's break handler ------------------------

    private void onEvent(TrackerEvent event) {
        if (event instanceof BlockBroken b) {
            guard(F_FLAG, () -> onBreak(b));
        } else if (event instanceof PlayerLeft l) {
            guard(F_FLAG, () -> onLeave(l.player()));
        }
    }

    private void onBreak(BlockBroken b) {
        ThreadLocal<?> tl = flag;
        if (tl == null || !Boolean.TRUE.equals(tl.get())) {
            return;
        }
        UUID player = b.player();
        if (player == null) {
            return;
        }
        long now = clock.getAsLong();
        Vein open = veins.get(player);
        if (open != null) {
            boolean done;
            synchronized (open) {
                if (!open.closed && now - open.last < GAP_MILLIS) {
                    open.add(b.blockId(), now);
                    return;
                }
                // an idle vein is ours to close unless the tick closed it first
                done = !open.closed;
                open.closed = true;
            }
            veins.remove(player, open);
            if (done) {
                publishVein(player, open);
            }
        }
        Vein vein = new Vein(b.blockId(), b.world(), b.creative());
        vein.add(b.blockId(), now);
        veins.put(player, vein);
    }

    private void onLeave(UUID player) {
        if (player == null) {
            return;
        }
        Vein open = veins.remove(player);
        if (open == null) {
            return;
        }
        boolean done;
        synchronized (open) {
            done = !open.closed;
            open.closed = true;
        }
        if (done) {
            publishVein(player, open);
        }
    }

    // ---- the tick, on the timer thread -------------------------------------------------------------------

    private void tickSafely() {
        guard(F_FLAG, this::tick);
    }

    /** Closes every vein idle for the gap. Package private so a test can call it in place of the timer. */
    void tick() {
        if (veins.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (Map.Entry<UUID, Vein> e : veins.entrySet()) {
            Vein open = e.getValue();
            synchronized (open) {
                if (open.closed || now - open.last < GAP_MILLIS) {
                    continue;
                }
                open.closed = true;
            }
            veins.remove(e.getKey(), open);
            publishVein(e.getKey(), open);
        }
    }

    // ---- publishing, from either thread -----------------------------------------------------------------

    private void publishVein(UUID player, Vein vein) {
        if (vein.count <= 0 || !isBound()) {
            return;
        }
        if (wants(EventKind.VEIN_MINED)) {
            publish(new VeinMined(header(vein.world, player), ID, vein.blockId, vein.count, vein.creative));
        }
        if (vein.ore > 0) {
            services.counters.add(player, ModKeys.key(ID, ORE_BLOCKS), vein.ore, vein.world);
        }
    }

    /** True for an ore block id as the mod's own ores mode reads it. */
    static boolean isOre(String blockId) {
        return blockId != null && blockId.contains("Ore_") && !blockId.contains("_Cracked");
    }

    /** One player's open vein. Guarded by its own monitor, the world thread and the tick both touch it. */
    private static final class Vein {
        final String blockId;
        final String world;
        /** The flag of the break that opened the vein. */
        final boolean creative;
        int count;
        int ore;
        long last;
        boolean closed;

        Vein(String blockId, String world, boolean creative) {
            this.blockId = blockId;
            this.world = world;
            this.creative = creative;
        }

        void add(String id, long now) {
            count++;
            if (isOre(id)) {
                ore++;
            }
            last = now;
        }
    }
}
