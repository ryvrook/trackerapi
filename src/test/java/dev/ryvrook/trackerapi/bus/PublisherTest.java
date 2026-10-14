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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Publisher publisher(int capacity, Runnable wake) {
        return new Publisher(TestSupport.LOGGER, new Perf(), capacity, wake);
    }

    /** A sink that wants a fixed set of kinds and counts what it got. */
    private static final class CountingSink implements Publisher.Sink {
        final Set<EventKind> kinds;
        final List<TrackerEvent> got = new ArrayList<>();

        CountingSink(Set<EventKind> kinds) {
            this.kinds = kinds;
        }

        @Override
        public boolean wants(EventKind kind) {
            return kinds.contains(kind);
        }

        @Override
        public void accept(TrackerEvent event) {
            got.add(event);
        }
    }

    @Test
    void nothingIsWantedUntilSomeoneAsks() {
        Publisher pub = publisher(1024, null);
        for (EventKind k : EventKind.values()) {
            assertFalse(pub.wants(k), k.id());
        }
        pub.publish(TestSupport.broken(P, "Rock_Stone"));
        assertEquals(0, pub.published(EventKind.BLOCK_BROKEN), "an unwanted kind is not even counted");
    }

    @Test
    void wantFlagsFollowSinksSubscriptionsAndTheConfig() {
        Publisher pub = publisher(1024, null);
        CountingSink sink = new CountingSink(EnumSet.of(EventKind.BLOCK_BROKEN));
        pub.addSink(sink);
        assertTrue(pub.wants(EventKind.BLOCK_BROKEN));
        assertFalse(pub.wants(EventKind.BLOCK_PLACED));

        Subscription sync = pub.subscribe("Test", EnumSet.of(EventKind.BLOCK_PLACED), Delivery.SYNC, e -> { });
        assertTrue(pub.wants(EventKind.BLOCK_PLACED));
        assertEquals(EventKind.BLOCK_PLACED, sync.kind());
        sync.close();
        assertFalse(pub.wants(EventKind.BLOCK_PLACED));
        assertFalse(sync.isActive());
        sync.close(); // twice is harmless

        Subscription all = pub.subscribe("Test", null, Delivery.ASYNC, e -> { });
        for (EventKind k : EventKind.values()) {
            assertTrue(pub.wants(k), k.id());
        }
        pub.setTracked(EnumSet.complementOf(EnumSet.of(EventKind.BLOCK_BROKEN)));
        assertFalse(pub.wants(EventKind.BLOCK_BROKEN), "a kind turned off in the config is never wanted");
        assertFalse(pub.isTracked(EventKind.BLOCK_BROKEN));
        assertTrue(pub.wants(EventKind.CHUNK_LOADED));
        pub.setTracked(EnumSet.allOf(EventKind.class));
        all.close();
        pub.removeSink(sink);
        assertFalse(pub.wants(EventKind.BLOCK_BROKEN));
    }

    @Test
    void publishFeedsSinksSyncListenersAndTheQueue() {
        AtomicInteger wakes = new AtomicInteger();
        Publisher pub = publisher(1024, wakes::incrementAndGet);
        CountingSink sink = new CountingSink(EnumSet.of(EventKind.BLOCK_BROKEN));
        pub.addSink(sink);
        List<TrackerEvent> sync = new ArrayList<>();
        List<Thread> syncThreads = new ArrayList<>();
        pub.subscribe("Sync", EnumSet.of(EventKind.BLOCK_BROKEN), Delivery.SYNC, e -> {
            sync.add(e);
            syncThreads.add(Thread.currentThread());
        });
        pub.subscribe("Async", EnumSet.of(EventKind.BLOCK_BROKEN, EventKind.PLAYER_JOINED), Delivery.ASYNC, e -> { });

        TrackerEvent e = TestSupport.broken(P, "Rock_Stone");
        pub.publish(e);
        pub.publish(new PlayerJoined(TestSupport.header("default", P), "Alder", true));

        assertEquals(List.of(e), sink.got);
        assertEquals(List.of(e), sync);
        assertSame(Thread.currentThread(), syncThreads.get(0), "sync listeners run on the publishing thread");
        assertEquals(2, pub.queued());
        assertEquals(2, wakes.get());
        assertSame(e, pub.queue().poll());
        assertEquals(1, pub.published(EventKind.BLOCK_BROKEN));
        assertEquals(1, pub.published(EventKind.PLAYER_JOINED));
        assertTrue(pub.nextSeq() > 0);
    }

    @Test
    void aFullQueueDropsAndCounts() {
        Publisher pub = publisher(4, null);
        pub.subscribe("Async", null, Delivery.ASYNC, e -> { });
        for (int i = 0; i < 10; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        assertEquals(4, pub.queued());
        assertEquals(4, pub.queueCapacity());
        assertEquals(10, pub.published(EventKind.BLOCK_BROKEN));
        assertEquals(6, pub.dropped(EventKind.BLOCK_BROKEN));
        pub.resetCounters();
        assertEquals(0, pub.dropped(EventKind.BLOCK_BROKEN));
    }

    @Test
    void aSinkOrListenerThatThrowsDoesNotStopTheOthers() {
        Publisher pub = publisher(1024, null);
        pub.addSink(new Publisher.Sink() {
            @Override
            public boolean wants(EventKind kind) {
                return true;
            }

            @Override
            public void accept(TrackerEvent event) {
                throw new IllegalStateException("broken sink");
            }
        });
        Subscription bad = pub.subscribe("Bad", null, Delivery.SYNC, e -> {
            throw new IllegalStateException("broken listener");
        });
        AtomicInteger good = new AtomicInteger();
        pub.subscribe("Good", null, Delivery.SYNC, e -> good.incrementAndGet());
        pub.publish(TestSupport.broken(P, "Rock_Stone"));
        assertEquals(1, good.get());
        assertEquals(1, bad.failures());
        assertEquals(0, bad.delivered());
    }

    @Test
    void aSlowSyncListenerIsSuspendedAfterAThousandCalls() {
        Publisher pub = publisher(1024, null);
        long spin = TimeUnit.MICROSECONDS.toNanos(Publisher.SYNC_SUSPEND_MEAN_NANOS / 1000 + 60);
        Subscription slow = pub.subscribe("Slow", EnumSet.of(EventKind.BLOCK_BROKEN), Delivery.SYNC, e -> {
            long until = System.nanoTime() + spin;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        });
        Subscription fast = pub.subscribe("Fast", EnumSet.of(EventKind.BLOCK_BROKEN), Delivery.SYNC, e -> { });
        for (int i = 0; i < Publisher.SYNC_WINDOW; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        assertEquals(Publisher.SYNC_WINDOW, slow.delivered());
        SubscriptionImpl impl = (SubscriptionImpl) slow;
        assertTrue(impl.suspendedUntil != 0, "suspended once the window closed over the limit");
        for (int i = 0; i < 100; i++) {
            pub.publish(TestSupport.broken(P, "Rock_Stone"));
        }
        assertEquals(Publisher.SYNC_WINDOW, slow.delivered(), "a suspended listener gets nothing");
        assertEquals(Publisher.SYNC_WINDOW + 100, fast.delivered(), "the fast listener next to it is untouched");
        assertEquals(0, ((SubscriptionImpl) fast).suspendedUntil);
        assertTrue(slow.isActive(), "suspension is not a close");

        impl.suspendedUntil = System.nanoTime() - 1; // the five minutes are over
        pub.publish(TestSupport.broken(P, "Rock_Stone"));
        assertEquals(Publisher.SYNC_WINDOW + 1, slow.delivered());
        assertEquals(0, impl.suspendedUntil);
    }

    @Test
    void closeDropsEverySubscriptionAndStopsPublishing() {
        Publisher pub = publisher(1024, null);
        CountingSink sink = new CountingSink(EnumSet.allOf(EventKind.class));
        pub.addSink(sink);
        Subscription a = pub.subscribe("A", null, Delivery.ASYNC, e -> { });
        Subscription b = pub.subscribe("B", null, Delivery.SYNC, e -> { });
        pub.close();
        assertTrue(pub.isClosed());
        assertFalse(a.isActive());
        assertFalse(b.isActive());
        assertTrue(pub.subscriptions().isEmpty());
        assertFalse(pub.wants(EventKind.BLOCK_BROKEN));
        pub.publish(TestSupport.broken(P, "Rock_Stone"));
        assertTrue(sink.got.isEmpty());
        assertEquals(0, pub.queued());
        assertThrows(IllegalStateException.class, () -> pub.subscribe("Late", null, Delivery.ASYNC, e -> { }));
        a.close(); // closing after the bus closed is harmless
    }
}
