package dev.ryvrook.trackerapi;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.BlockBrokenByWorld;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.BlockUsed;
import dev.ryvrook.trackerapi.api.event.ChunkGenerated;
import dev.ryvrook.trackerapi.api.event.CreatureSpawned;
import dev.ryvrook.trackerapi.api.event.CreatureTamed;
import dev.ryvrook.trackerapi.api.event.DamageDealt;
import dev.ryvrook.trackerapi.api.event.EffectChanged;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.GameModeChanged;
import dev.ryvrook.trackerapi.api.event.GameTimeSampled;
import dev.ryvrook.trackerapi.api.event.InteractionStarted;
import dev.ryvrook.trackerapi.api.event.ItemConsumed;
import dev.ryvrook.trackerapi.api.event.ItemCrafted;
import dev.ryvrook.trackerapi.api.event.ItemDropped;
import dev.ryvrook.trackerapi.api.event.ItemPickedUp;
import dev.ryvrook.trackerapi.api.event.ItemsGained;
import dev.ryvrook.trackerapi.api.event.ItemsLost;
import dev.ryvrook.trackerapi.api.event.MemoryRecorded;
import dev.ryvrook.trackerapi.api.event.MoonPhaseChanged;
import dev.ryvrook.trackerapi.api.event.MountChanged;
import dev.ryvrook.trackerapi.api.event.MovementEdge;
import dev.ryvrook.trackerapi.api.event.PlayerChatted;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.api.event.PlayerMoved;
import dev.ryvrook.trackerapi.api.event.PlayerRespawned;
import dev.ryvrook.trackerapi.api.event.PlayerSlept;
import dev.ryvrook.trackerapi.api.event.PlayerTeleported;
import dev.ryvrook.trackerapi.api.event.PlayerWorldChanged;
import dev.ryvrook.trackerapi.api.event.ProjectileLaunched;
import dev.ryvrook.trackerapi.api.event.VitalsSampled;
import dev.ryvrook.trackerapi.api.event.WeatherChanged;
import dev.ryvrook.trackerapi.api.event.WorldAdded;
import dev.ryvrook.trackerapi.api.event.WorldEventEnded;
import dev.ryvrook.trackerapi.api.event.WorldEventStarted;
import dev.ryvrook.trackerapi.api.event.WorldRemoved;
import dev.ryvrook.trackerapi.api.event.ZoneDiscovered;
import dev.ryvrook.trackerapi.bus.Dispatcher;
import dev.ryvrook.trackerapi.bus.Publisher;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dispatch thread with the subscriptions the suite opens on a server that runs every mod. Feats takes
 * 19 kinds, Chronicle Stats 34, Atlas 3 and Coinage 2, one subscription per kind, and Dominion one
 * subscription for its four gather kinds, 59 in all. The events are queued first and then drained, so the time is the dispatch thread's own
 * cost per event, not the publisher's. Run with -Pbench for a million events and the numbers in the console.
 */
class DispatchBenchmarkTest {
    private static final boolean BENCH = Boolean.getBoolean("trackerapi.bench");
    private static final int EVENTS = BENCH ? 1_000_000 : 50_000;

    private static final List<Class<? extends TrackerEvent>> FEATS = List.of(BlockBroken.class, BlockPlaced.class,
            BlockUsed.class, CreatureTamed.class, DamageDealt.class, EntityDied.class, GameModeChanged.class,
            InteractionStarted.class, ItemConsumed.class, ItemCrafted.class, ItemPickedUp.class, ItemsGained.class,
            MemoryRecorded.class, PlayerChatted.class, PlayerMoved.class, PlayerWorldChanged.class, WorldAdded.class,
            WorldRemoved.class, ZoneDiscovered.class);
    private static final List<Class<? extends TrackerEvent>> CHRONICLE = List.of(BlockBroken.class,
            BlockBrokenByWorld.class, BlockPlaced.class, BlockUsed.class, ChunkGenerated.class, CreatureSpawned.class,
            DamageDealt.class, EffectChanged.class, EntityDied.class, GameTimeSampled.class, InteractionStarted.class,
            ItemConsumed.class, ItemCrafted.class, ItemDropped.class, ItemPickedUp.class, ItemsGained.class,
            ItemsLost.class, MemoryRecorded.class, MoonPhaseChanged.class, MountChanged.class, MovementEdge.class,
            PlayerChatted.class, PlayerLeft.class, PlayerMoved.class, PlayerRespawned.class, PlayerSlept.class,
            PlayerTeleported.class, ProjectileLaunched.class, VitalsSampled.class, WeatherChanged.class,
            WorldEventEnded.class, WorldEventStarted.class, WorldRemoved.class, ZoneDiscovered.class);
    private static final List<Class<? extends TrackerEvent>> ATLAS = List.of(BlockBroken.class, BlockPlaced.class,
            EntityDied.class);
    private static final List<Class<? extends TrackerEvent>> COINAGE = List.of(EntityDied.class, BlockBroken.class);
    private static final EnumSet<EventKind> DOMINION = EnumSet.of(EventKind.BLOCK_BROKEN, EventKind.BLOCK_PLACED,
            EventKind.ITEM_PICKED_UP, EventKind.ITEM_DROPPED);

    @Test
    void theSuiteConsumersDrainABlockStream() {
        Perf perf = new Perf();
        Publisher publisher = new Publisher(TestSupport.LOGGER, perf, 1 << 20, null);
        Dispatcher dispatcher = new Dispatcher(publisher, TestSupport.LOGGER, perf);
        AtomicLong delivered = new AtomicLong();
        int subscriptions = 0;
        for (List<Class<? extends TrackerEvent>> consumer : List.of(FEATS, CHRONICLE, ATLAS, COINAGE)) {
            for (Class<? extends TrackerEvent> type : consumer) {
                publisher.subscribe("Bench", EnumSet.of(EventKinds.of(type)), Delivery.ASYNC, e -> delivered.incrementAndGet());
                subscriptions++;
            }
        }
        publisher.subscribe("Bench", DOMINION, Delivery.ASYNC, e -> delivered.incrementAndGet());
        subscriptions++;
        UUID player = UUID.randomUUID();
        for (int i = 0; i < EVENTS; i++) {
            if ((i & 1) == 0) {
                publisher.publish(new BlockBroken(TestSupport.header("default", player), i, 64, -i, "Rock_Stone",
                        "Tool_Pickaxe_Iron", false, false));
            } else {
                publisher.publish(new BlockPlaced(TestSupport.header("default", player), i, 64, -i, "Rock_Stone",
                        "Rock_Stone", false, false));
            }
        }
        // Breaks reach Feats, Chronicle, Atlas, Coinage and Dominion, places all but Coinage.
        long expected = (long) (EVENTS / 2) * 5 + (long) (EVENTS / 2) * 4;
        long start = System.nanoTime();
        dispatcher.start();
        while (delivered.get() < expected && System.nanoTime() - start < 60_000_000_000L) {
            Thread.onSpinWait();
        }
        long nanos = System.nanoTime() - start;
        dispatcher.stop(10_000);
        assertEquals(expected, delivered.get());
        assertEquals(0, publisher.dropped(EventKind.BLOCK_BROKEN));

        if (BENCH) {
            String out = String.format(Locale.ROOT, "TrackerAPI dispatch benchmark, %,d block events through %d subscriptions "
                    + "of the suite%n  %-14s %8.1f ms  %7.1f ns/event%n", EVENTS, subscriptions, "drain",
                    nanos / 1e6, (double) nanos / EVENTS);
            System.out.print(out);
        }
    }
}
