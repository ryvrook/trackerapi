package dev.ryvrook.trackerapi;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.bus.Dispatcher;
import dev.ryvrook.trackerapi.bus.Publisher;
import dev.ryvrook.trackerapi.counters.CounterSink;
import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pushes block breaks through the whole publishing side, the want check, the record, the header, the
 * counter sink with its per id and per world keys and the offer to the async queue, with one async
 * subscriber draining on the dispatch thread, as a busy server does. Prints the time and the bytes
 * allocated per event on the publishing thread. The target is under 200 ns and under 150 bytes.
 *
 * <p>The counts are always checked with a short run. Run with -Pbench for a million events, the numbers
 * in the console and in build/benchmark.txt.
 */
class TrackingBenchmarkTest {
    private static final boolean BENCH = Boolean.getBoolean("trackerapi.bench");
    private static final int RUNS = BENCH ? 1_000_000 : 50_000;
    private static final int WARMUP = BENCH ? 200_000 : 10_000;

    private static final String[] BLOCKS = {
            "Rock_Stone", "Soil_Dirt", "Ore_Copper_Stone", "Wood_Oak_Trunk", "Rock_Crystal_Void_Large",
            "Soil_Grass", "Ore_Iron_Basalt", "Rock_Basalt"};
    private static final String[] WORLDS = {"default", "Instance_Dungeon"};

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @TempDir
    Path dir;

    @Test
    void blockBreaks() throws IOException {
        Perf perf = new Perf();
        Dispatcher[] dispatcher = new Dispatcher[1];
        Publisher publisher = new Publisher(TestSupport.LOGGER, perf, 65_536, () -> dispatcher[0].wake());
        dispatcher[0] = new Dispatcher(publisher, TestSupport.LOGGER, perf);
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, TestSupport.DIRECT, perf);
        store.loadServer();
        CounterSink sink = new CounterSink(store);
        publisher.addSink(sink);
        AtomicLong received = new AtomicLong();
        Subscription sub = publisher.subscribe("Bench", EnumSet.of(EventKind.BLOCK_BROKEN), Delivery.ASYNC,
                e -> received.incrementAndGet());
        dispatcher[0].start();
        UUID uuid = UUID.randomUUID();
        store.join(uuid, "Bench", System.currentTimeMillis());

        breakBlocks(publisher, uuid, WARMUP);
        long thread = Thread.currentThread().threadId();
        long bytesBefore = THREADS.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        breakBlocks(publisher, uuid, RUNS);
        long nanos = System.nanoTime() - start;
        long bytes = THREADS.getThreadAllocatedBytes(thread) - bytesBefore;
        dispatcher[0].stop(10_000);

        if (BENCH) {
            String out = String.format(Locale.ROOT, "TrackerAPI publishing benchmark, %,d block breaks with the counter sink "
                            + "and one async subscriber%n  %-14s %8.1f ms  %7.1f ns/event  %8.1f bytes/event%n"
                            + "  delivered %,d, dropped %,d%n", RUNS, "block break", nanos / 1e6, (double) nanos / RUNS,
                    (double) bytes / RUNS, received.get(), publisher.dropped(EventKind.BLOCK_BROKEN));
            System.out.print(out);
            Path report = Path.of("build", "benchmark.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, out, StandardCharsets.UTF_8);
        }

        long total = RUNS + WARMUP;
        assertEquals(total, publisher.published(EventKind.BLOCK_BROKEN));
        assertEquals(total, store.get(uuid, Keys.BLOCK_BROKEN));
        assertEquals(total, store.server(Keys.BLOCK_BROKEN));
        long perId = 0;
        for (String b : BLOCKS) {
            perId += store.get(uuid, "block_broken:" + b);
        }
        assertEquals(total, perId);
        assertEquals(total, store.get(uuid, "world:default:block_broken") + store.get(uuid, "world:instance_dungeon:block_broken"));
        assertEquals(total, received.get() + publisher.dropped(EventKind.BLOCK_BROKEN),
                "every event reached the subscriber or was counted as dropped");
        assertEquals(total, sub.delivered() + publisher.dropped(EventKind.BLOCK_BROKEN));
        assertTrue(received.get() > 0);
    }

    private static void breakBlocks(Publisher publisher, UUID uuid, int n) {
        for (int i = 0; i < n; i++) {
            if (!publisher.wants(EventKind.BLOCK_BROKEN)) {
                continue;
            }
            publisher.publish(new BlockBroken(new Header(System.currentTimeMillis(), publisher.nextSeq(), WORLDS[(i >> 3) & 1], uuid), i, 64, -i,
                    BLOCKS[i & 7], "Tool_Pickaxe_Iron", false, false));
        }
    }
}
