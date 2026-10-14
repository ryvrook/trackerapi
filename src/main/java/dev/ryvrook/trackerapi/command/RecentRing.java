package dev.ryvrook.trackerapi.command;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.bus.Publisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;

/**
 * The last 256 events of each kind, in memory, for {@code /trackerapi recent}. It keeps the kinds some other
 * sink already wants, the counters or the journal, so it never makes a hook build a record nobody else
 * asked for. A kind nobody wants is armed the first time someone asks for it and kept from then on.
 *
 * <p>Thread safe. {@link #accept} runs on the publishing threads and costs one atomic add and one store.
 */
public final class RecentRing implements Publisher.Sink {
    static final int SIZE = 256;
    private static final int MASK = SIZE - 1;

    private final AtomicReferenceArray<TrackerEvent>[] rings;
    private final AtomicLong[] next = new AtomicLong[EventKind.COUNT];
    private final boolean[] armed = new boolean[EventKind.COUNT];
    private final Predicate<EventKind> others;
    private volatile boolean[] armedView = new boolean[EventKind.COUNT];

    /** {@code others} answers whether another sink wants a kind already. */
    @SuppressWarnings("unchecked")
    public RecentRing(Predicate<EventKind> others) {
        this.others = others;
        this.rings = new AtomicReferenceArray[EventKind.COUNT];
        for (int i = 0; i < EventKind.COUNT; i++) {
            rings[i] = new AtomicReferenceArray<>(SIZE);
            next[i] = new AtomicLong();
        }
    }

    @Override
    public boolean wants(EventKind kind) {
        return armedView[kind.ordinal()] || others.test(kind);
    }

    @Override
    public void accept(TrackerEvent event) {
        int k = event.kind().ordinal();
        long i = next[k].getAndIncrement();
        rings[k].set((int) (i & MASK), event);
    }

    /** Keeps the kind from now on. True when it was not kept before, so the caller rebuilds the want flags. */
    public synchronized boolean arm(EventKind kind) {
        if (armed[kind.ordinal()]) {
            return false;
        }
        armed[kind.ordinal()] = true;
        armedView = armed.clone();
        return true;
    }

    /** How many events of the kind were seen since start. */
    public long seen(EventKind kind) {
        return next[kind.ordinal()].get();
    }

    /** The last {@code n} events of a kind, newest first. */
    public List<TrackerEvent> last(EventKind kind, int n) {
        int k = kind.ordinal();
        long end = next[k].get();
        int count = (int) Math.min(Math.min(n, SIZE), end);
        List<TrackerEvent> out = new ArrayList<>(Math.max(0, count));
        for (long i = end - 1; i >= end - count && i >= 0; i--) {
            TrackerEvent e = rings[k].get((int) (i & MASK));
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }
}
