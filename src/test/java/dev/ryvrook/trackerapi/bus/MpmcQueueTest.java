package dev.ryvrook.trackerapi.bus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpmcQueueTest {

    @Test
    void capacityIsRoundedUpToAPowerOfTwo() {
        assertEquals(2, new MpmcQueue<>(0).capacity());
        assertEquals(2, new MpmcQueue<>(2).capacity());
        assertEquals(8, new MpmcQueue<>(5).capacity());
        assertEquals(1024, new MpmcQueue<>(1024).capacity());
        assertEquals(2048, new MpmcQueue<>(1025).capacity());
    }

    @Test
    void singleProducerKeepsOrderAcrossManyLaps() {
        MpmcQueue<Integer> q = new MpmcQueue<>(8);
        assertTrue(q.isEmpty());
        assertNull(q.poll());
        int next = 0;
        int expected = 0;
        for (int lap = 0; lap < 100; lap++) {
            for (int i = 0; i < 5; i++) {
                assertTrue(q.offer(next++));
            }
            assertEquals(5, q.size());
            for (int i = 0; i < 5; i++) {
                assertEquals(expected++, q.poll());
            }
            assertTrue(q.isEmpty());
        }
    }

    @Test
    void fullQueueRefusesAtOnceAndTakesAgainAfterAPoll() {
        MpmcQueue<String> q = new MpmcQueue<>(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(q.offer("e" + i));
        }
        assertEquals(4, q.size());
        assertFalse(q.offer("over"));
        assertEquals("e0", q.poll());
        assertTrue(q.offer("e4"));
        assertFalse(q.offer("over"));
        for (int i = 1; i <= 4; i++) {
            assertEquals("e" + i, q.poll());
        }
        assertNull(q.poll());
    }

    @Test
    void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> new MpmcQueue<String>(4).offer(null));
    }

    @Test
    void manyProducersLoseNothingAndKeepOrderPerProducer() throws Exception {
        int producers = 4;
        int each = 200_000;
        MpmcQueue<long[]> q = new MpmcQueue<>(1024);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int id = p;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    long[] item = {id, i};
                    while (!q.offer(item)) {
                        Thread.onSpinWait();
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        long[] last = new long[producers];
        java.util.Arrays.fill(last, -1);
        AtomicBoolean ordered = new AtomicBoolean(true);
        start.countDown();
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received < producers * each && System.nanoTime() < deadline) {
            long[] item = q.poll();
            if (item == null) {
                Thread.onSpinWait();
                continue;
            }
            int p = (int) item[0];
            if (item[1] != last[p] + 1) {
                ordered.set(false);
            }
            last[p] = item[1];
            received++;
        }
        for (Thread t : threads) {
            t.join(5_000);
        }
        assertEquals(producers * each, received);
        assertTrue(ordered.get(), "each producer's items arrive in the order it offered them");
        for (long l : last) {
            assertEquals(each - 1, l);
        }
        assertTrue(q.isEmpty());
    }

    @Test
    void manyConsumersTakeEveryItemOnce() throws Exception {
        int items = 100_000;
        MpmcQueue<Integer> q = new MpmcQueue<>(items);
        for (int i = 0; i < items; i++) {
            assertTrue(q.offer(i));
        }
        boolean[] seen = new boolean[items];
        java.util.concurrent.atomic.AtomicInteger dupes = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            Thread t = new Thread(() -> {
                Integer v;
                while ((v = q.poll()) != null) {
                    synchronized (seen) {
                        if (seen[v]) {
                            dupes.incrementAndGet();
                        }
                        seen[v] = true;
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(0, dupes.get());
        for (boolean b : seen) {
            assertTrue(b);
        }
    }
}
