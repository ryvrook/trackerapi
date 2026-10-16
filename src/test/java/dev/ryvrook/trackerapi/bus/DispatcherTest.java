package dev.ryvrook.trackerapi.bus;

import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final Perf perf = new Perf();

    private Publisher publisher(int capacity) {
        return new Publisher(TestSupport.LOGGER, perf, capacity, null);
    }

    /** Drains what is queued on the calling thread. A dispatcher told to stop returns once the queue is empty. */
    private static void drainHere(Dispatcher d) {
        d.stop(0);
        d.run();
    }

    private long dispatchBatches() {
        return perf.rows(System.nanoTime()).stream().filter(r -> r.name().equals("dispatch"))
                .mapToLong(Perf.Row::calls).sum();
    }

    @Test
    void deliversInBatchesInOrderToEveryListener() {
        Publisher pub = publisher(4096);
        List<Long> first = new ArrayList<>();
        AtomicInteger second = new AtomicInteger();
        pub.subscribe("First", EnumSet.of(EventKind.BLOCK_BROKEN), Delivery.ASYNC, e -> first.add(e.seq()));
        pub.subscribe("Second", null, Delivery.ASYNC, e -> second.incrementAndGet());
        int n = Dispatcher.BATCH * 2 + 100;
        for (int i = 0; i < n; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        pub.publish(new PlayerJoined(TestSupport.header("default", P), "Alder", false));
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        drainHere(d);

        assertEquals(n, first.size(), "the first listener only gets the kind it asked for");
        for (int i = 1; i < first.size(); i++) {
            assertTrue(first.get(i) > first.get(i - 1), "in publish order");
        }
        assertEquals(n + 1, second.get());
        assertEquals(3, dispatchBatches(), "1,124 events go out in batches of at most 512");
        assertEquals(0, pub.queued());
    }

    @Test
    void theThreadDeliversAndStopsAfterDraining() throws Exception {
        Dispatcher[] dh = new Dispatcher[1];
        Publisher pub = new Publisher(TestSupport.LOGGER, perf, 4096, () -> dh[0].wake());
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        dh[0] = d;
        CountDownLatch got = new CountDownLatch(1000);
        List<String> threads = new ArrayList<>();
        pub.subscribe("Async", null, Delivery.ASYNC, e -> {
            if (threads.isEmpty()) {
                threads.add(Thread.currentThread().getName());
            }
            got.countDown();
        });
        d.start();
        for (int i = 0; i < 1000; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        assertTrue(got.await(5, TimeUnit.SECONDS));
        d.stop(5_000);
        assertEquals("TrackerAPI-dispatch", threads.get(0));
    }

    @Test
    void aListenerOverBudgetForTwentyBatchesIsSuspended() {
        int batches = Dispatcher.SUSPEND_AFTER + 5;
        Publisher pub = publisher(Dispatcher.BATCH * batches);
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        d.setBudgetMillis(1);
        AtomicInteger slowCalls = new AtomicInteger();
        Subscription slow = pub.subscribe("Slow", null, Delivery.ASYNC, e -> {
            if (slowCalls.getAndIncrement() % Dispatcher.BATCH == 0) {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
            }
        });
        Subscription fast = pub.subscribe("Fast", null, Delivery.ASYNC, e -> { });
        for (int i = 0; i < Dispatcher.BATCH * batches; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        assertEquals(0, pub.dropped(EventKind.BLOCK_BROKEN));
        drainHere(d);

        assertEquals((long) Dispatcher.BATCH * Dispatcher.SUSPEND_AFTER, slow.delivered(),
                "delivered until the twentieth batch over budget, then skipped");
        assertTrue(((SubscriptionImpl) slow).suspendedUntil != 0);
        assertTrue(slow.isActive());
        assertEquals((long) Dispatcher.BATCH * batches, fast.delivered(), "the other listener got every batch");
    }

    @Test
    void aBatchUnderBudgetResetsTheRun() {
        Publisher pub = publisher(Dispatcher.BATCH * 64);
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        d.setBudgetMillis(1);
        AtomicInteger calls = new AtomicInteger();
        Subscription s = pub.subscribe("Sometimes", null, Delivery.ASYNC, e -> {
            int c = calls.getAndIncrement();
            // slow in nineteen batches out of every twenty, never twenty in a row
            if (c % Dispatcher.BATCH == 0 && (c / Dispatcher.BATCH) % Dispatcher.SUSPEND_AFTER != 0) {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
            }
        });
        int batches = Dispatcher.SUSPEND_AFTER * 2 + 1;
        for (int i = 0; i < Dispatcher.BATCH * batches; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        drainHere(d);
        assertEquals((long) Dispatcher.BATCH * batches, s.delivered());
        assertEquals(0, ((SubscriptionImpl) s).suspendedUntil);
    }

    @Test
    void aBatchWithoutAListenersKindsSkipsItAndLeavesItsRunAlone() {
        int pairs = Dispatcher.SUSPEND_AFTER;
        Publisher pub = publisher(Dispatcher.BATCH * pairs * 2);
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        d.setBudgetMillis(1);
        AtomicInteger calls = new AtomicInteger();
        Subscription slow = pub.subscribe("Breaks", EnumSet.of(EventKind.BLOCK_BROKEN), Delivery.ASYNC, e -> {
            if (calls.getAndIncrement() % Dispatcher.BATCH == 0) {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
            }
        });
        List<Long> joins = new ArrayList<>();
        pub.subscribe("Joins", EnumSet.of(EventKind.PLAYER_JOINED), Delivery.ASYNC, e -> joins.add(e.seq()));
        // A batch of breaks then a batch of joins, twenty times. The joins never reach the break listener.
        for (int p = 0; p < pairs; p++) {
            for (int i = 0; i < Dispatcher.BATCH; i++) {
                pub.publish(TestSupport.broken(P, "Rock_Stone"));
            }
            for (int i = 0; i < Dispatcher.BATCH; i++) {
                pub.publish(new PlayerJoined(TestSupport.header("default", P), "Alder", false));
            }
        }
        drainHere(d);

        assertEquals((long) Dispatcher.BATCH * pairs, slow.delivered());
        assertTrue(((SubscriptionImpl) slow).suspendedUntil != 0,
                "twenty batches of breaks over budget suspend it, the join batches between them do not reset the run");
        assertEquals((long) Dispatcher.BATCH * pairs, joins.size());
        for (int i = 1; i < joins.size(); i++) {
            assertTrue(joins.get(i) > joins.get(i - 1), "in publish order");
        }
    }

    @Test
    void aClosedListenerStopsMidBatchAndAFailingOneIsCounted() {
        Publisher pub = publisher(4096);
        Subscription[] self = new Subscription[1];
        AtomicInteger got = new AtomicInteger();
        self[0] = pub.subscribe("Closer", null, Delivery.ASYNC, e -> {
            if (got.incrementAndGet() == 10) {
                self[0].close();
            }
        });
        Subscription failing = pub.subscribe("Failing", null, Delivery.ASYNC, e -> {
            throw new IllegalStateException("listener bug");
        });
        for (int i = 0; i < 100; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        drainHere(new Dispatcher(pub, TestSupport.LOGGER, perf));
        assertEquals(10, got.get());
        assertEquals(100, failing.failures());
        assertTrue(failing.isActive(), "failures alone do not close a listener");
    }

    @Test
    void closingThePublisherDropsAsyncListeners() {
        Publisher pub = publisher(4096);
        List<TrackerEvent> got = new ArrayList<>();
        Subscription s = pub.subscribe("Async", null, Delivery.ASYNC, got::add);
        for (int i = 0; i < 10; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        pub.close();
        drainHere(new Dispatcher(pub, TestSupport.LOGGER, perf));
        assertTrue(got.isEmpty(), "nothing reaches a listener after the bus closed");
        assertTrue(!s.isActive());
        assertEquals(0, pub.asyncSubscriptions().length);
    }

    @Test
    void aHandOverLeavesTheQueueToTheShutdownThread() throws Exception {
        Dispatcher[] dh = new Dispatcher[1];
        Publisher pub = new Publisher(TestSupport.LOGGER, perf, 4096, () -> dh[0].wake());
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        dh[0] = d;
        List<String> threads = new ArrayList<>();
        pub.subscribe("Async", null, Delivery.ASYNC, e -> threads.add(Thread.currentThread().getName()));
        d.start();
        assertTrue(d.handOver(2_000), "an idle thread ends at once");
        for (int i = 0; i < 10; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        Thread.sleep(50);
        assertTrue(threads.isEmpty(), "nothing is delivered after the hand over until the shutdown drains");
        assertEquals(10, pub.queued());

        d.drainHere();
        assertEquals(10, threads.size());
        assertTrue(threads.stream().allMatch(Thread.currentThread().getName()::equals), "delivered on the caller");
        assertEquals(0, pub.queued());
    }

    @Test
    void aHandOverWaitsNoLongerThanAskedForAListenerThatHangs() throws Exception {
        Dispatcher[] dh = new Dispatcher[1];
        Publisher pub = new Publisher(TestSupport.LOGGER, perf, 4096, () -> dh[0].wake());
        Dispatcher d = new Dispatcher(pub, TestSupport.LOGGER, perf);
        dh[0] = d;
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pub.subscribe("Stuck", null, Delivery.ASYNC, e -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        d.start();
        pub.publish(TestSupport.broken(P, "Rock_Stone"));
        assertTrue(inside.await(2, TimeUnit.SECONDS));
        long start = System.nanoTime();
        assertEquals(false, d.handOver(100), "the thread is still inside the listener");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        release.countDown();
        d.stop(2_000);
    }
}
