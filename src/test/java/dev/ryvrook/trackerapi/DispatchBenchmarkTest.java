package dev.ryvrook.trackerapi;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.*;
import dev.ryvrook.trackerapi.bus.Dispatcher;
import dev.ryvrook.trackerapi.bus.Publisher;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dispatch thread with the subscriptions the suite opens on a server that runs every mod. Feats takes
 * 18 kinds, Chronicle Stats 32, Atlas and Coinage a few each, all one subscription per kind. The events
 * are queued first and then drained, so the time is the dispatch thread's own cost per event, not the
 * publisher's. Run with -Pbench for a million events and the numbers in build/benchmark.txt.
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

    @Test
    void fiveConsumersDrainABlockStream() throws IOException {
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
        // Breaks reach Feats, Chronicle, Atlas and Coinage, places the first three.
        long expected = (long) (EVENTS / 2) * 4 + (long) (EVENTS / 2) * 3;
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
                    + "of four consumers%n  %-14s %8.1f ms  %7.1f ns/event%n", EVENTS, subscriptions, "drain",
                    nanos / 1e6, (double) nanos / EVENTS);
            System.out.print(out);
            Path report = Path.of("build", "benchmark.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
