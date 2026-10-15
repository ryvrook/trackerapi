package dev.ryvrook.trackerapi.bus;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.util.Guard;
import dev.ryvrook.trackerapi.util.Perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The one place events enter. Every hook asks {@link #wants(EventKind)} first, which is one volatile
 * array read, and only builds its event record when someone wants it. {@link #publish} then stamps the
 * sequence number, hands the event to the sync listeners on the calling thread, offers it to the async
 * queue for the dispatch thread, and to the sinks (the counters and the journal) that registered for the
 * kind.
 *
 * <p>The want flags are recomputed whenever a subscription opens or closes, a sink changes, or the config
 * turns a kind off. A kind nobody wants costs the hook one volatile read and nothing else, no allocation,
 * no component reads beyond the ones the engine already handed it.
 *
 * <p>Thread safe. Hooks publish from world threads and the network thread, subscriptions change from any
 * thread.
 */
public final class Publisher {
    /** A sync listener whose mean call cost crosses this is suspended for five minutes. */
    static final long SYNC_SUSPEND_MEAN_NANOS = TimeUnit.MICROSECONDS.toNanos(200);
    static final long SYNC_SUSPEND_NANOS = TimeUnit.MINUTES.toNanos(5);
    /** The mean is judged after this many calls, then the window restarts. */
    static final int SYNC_WINDOW = 1_000;

    /** A sink receives every published event of the kinds it asked for, on the publishing thread. Internal. */
    public interface Sink {
        boolean wants(EventKind kind);

        void accept(TrackerEvent event);
    }

    private final HytaleLogger logger;
    private final Perf perf;
    private final MpmcQueue<TrackerEvent> queue;
    private final AtomicLong seq = new AtomicLong();
    private final LongAdder[] published = new LongAdder[EventKind.COUNT];
    private final LongAdder[] dropped = new LongAdder[EventKind.COUNT];
    private final List<SubscriptionImpl> subscriptions = new CopyOnWriteArrayList<>();
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private final Guard.RateLimiter warnings = new Guard.RateLimiter(TimeUnit.MINUTES.toNanos(1));
    /** One entry per kind. Bit 0 any want, bit 1 sync listeners, bit 2 async listeners, bit 3 sinks. */
    private volatile byte[] wants = new byte[EventKind.COUNT];
    private volatile boolean[] tracked = new boolean[EventKind.COUNT];
    private volatile boolean closed;
    /** Per kind sync listener lists, rebuilt on change so the hot path iterates a plain array. */
    private volatile SubscriptionImpl[][] syncByKind = new SubscriptionImpl[EventKind.COUNT][];
    /** The active async subscriptions in subscription order, rebuilt on change for the dispatcher. */
    private volatile SubscriptionImpl[] async = new SubscriptionImpl[0];
    private final Runnable wake;

    public Publisher(HytaleLogger logger, Perf perf, int queueCapacity, Runnable wake) {
        this.logger = logger;
        this.perf = perf;
        this.queue = new MpmcQueue<>(queueCapacity);
        this.wake = wake == null ? () -> { } : wake;
        for (int i = 0; i < EventKind.COUNT; i++) {
            published[i] = new LongAdder();
            dropped[i] = new LongAdder();
        }
        java.util.Arrays.fill(tracked, true);
        rebuild();
    }

    // ---- hot path --------------------------------------------------------------------------------------

    /** True when anything wants this kind. One volatile read, call it before building the event. */
    public boolean wants(EventKind kind) {
        return wants[kind.ordinal()] != 0;
    }

    /** The next sequence number, for the event record being built. */
    public long nextSeq() {
        return seq.incrementAndGet();
    }

    /** The current time, for the event record being built. */
    public static long now() {
        return System.currentTimeMillis();
    }

    /** Publishes a built event. Safe from any thread. */
    public void publish(TrackerEvent event) {
        if (closed || event == null) {
            return;
        }
        int ordinal = event.kind().ordinal();
        byte w = wants[ordinal];
        if (w == 0) {
            return;
        }
        published[ordinal].increment();
        if ((w & 8) != 0) {
            for (Sink sink : sinks) {
                if (sink.wants(event.kind())) {
                    try {
                        sink.accept(event);
                    } catch (RuntimeException | LinkageError e) {
                        Guard.failed(e, "A TrackerAPI sink failed");
                    }
                }
            }
        }
        if ((w & 2) != 0) {
            deliverSync(event, syncByKind[ordinal]);
        }
        if ((w & 4) != 0) {
            if (queue.offer(event)) {
                wake.run();
            } else {
                dropped[ordinal].increment();
                if (warnings.allow("full")) {
                    logger.at(Level.WARNING).log("TrackerAPI async queue is full (%d events), dropping %s events until the dispatch thread catches up",
                            queue.capacity(), event.kind().id());
                }
            }
        }
    }

    private void deliverSync(TrackerEvent event, SubscriptionImpl[] listeners) {
        if (listeners == null) {
            return;
        }
        long now = System.nanoTime();
        for (SubscriptionImpl s : listeners) {
            if (!s.isActive()) {
                continue;
            }
            long until = s.suspendedUntil;
            if (until != 0) {
                if (now < until) {
                    continue;
                }
                s.suspendedUntil = 0;
                s.syncNanosTotal = 0;
                s.syncCalls = 0;
            }
            long start = System.nanoTime();
            boolean failed = s.deliver(event);
            long took = System.nanoTime() - start;
            perf.record(Perf.R_SYNC_LISTENERS, took);
            if (failed && warnings.allow("fail:" + s.owner)) {
                logger.at(Level.WARNING).withCause(s.lastFailure).log("TrackerAPI sync listener of %s failed", s.owner);
            }
            // Only the publishing thread for a given kind touches these two, and several world threads
            // may share a listener, so the window is a rough guide rather than an exact mean.
            long total = s.syncNanosTotal + took;
            long calls = s.syncCalls + 1;
            if (calls >= SYNC_WINDOW) {
                if (total / calls > SYNC_SUSPEND_MEAN_NANOS) {
                    s.suspendedUntil = System.nanoTime() + SYNC_SUSPEND_NANOS;
                    logger.at(Level.WARNING).log("TrackerAPI suspended the sync listener of %s for 5 minutes, it averaged %d microseconds per event on an engine thread",
                            s.owner, total / calls / 1000);
                }
                total = 0;
                calls = 0;
            }
            s.syncNanosTotal = total;
            s.syncCalls = calls;
        }
    }

    // ---- subscriptions ---------------------------------------------------------------------------------

    public Subscription subscribe(String owner, Set<EventKind> kinds, Delivery delivery, Consumer<TrackerEvent> listener) {
        if (closed) {
            throw new IllegalStateException("TrackerAPI is stopped");
        }
        SubscriptionImpl[] holder = new SubscriptionImpl[1];
        SubscriptionImpl s = new SubscriptionImpl(owner == null ? "unknown" : owner, kinds, delivery, listener, () -> {
            subscriptions.remove(holder[0]);
            rebuild();
        });
        holder[0] = s;
        subscriptions.add(s);
        rebuild();
        return s;
    }

    public List<Subscription> subscriptions() {
        return new ArrayList<>(subscriptions);
    }

    /** The async subscriptions, for the dispatcher. An array kept from the last change, nothing is copied. */
    SubscriptionImpl[] asyncSubscriptions() {
        return async;
    }

    public void addSink(Sink sink) {
        sinks.add(sink);
        rebuild();
    }

    public void removeSink(Sink sink) {
        sinks.remove(sink);
        rebuild();
    }

    /** Turns kinds on or off from the config. A kind that is off is never published. */
    public void setTracked(Set<EventKind> on) {
        boolean[] next = new boolean[EventKind.COUNT];
        for (EventKind k : on) {
            next[k.ordinal()] = true;
        }
        tracked = next;
        rebuild();
    }

    public boolean isTracked(EventKind kind) {
        return tracked[kind.ordinal()];
    }

    /**
     * Sinks changed what they want, for example the journal kinds after a reload. Synchronized, since two
     * threads subscribing at once would otherwise race and the last one to write could leave out the other.
     */
    public synchronized void rebuild() {
        if (closed) {
            wants = new byte[EventKind.COUNT];
            return;
        }
        List<SubscriptionImpl> asyncList = new ArrayList<>();
        for (SubscriptionImpl s : subscriptions) {
            if (s.delivery == Delivery.ASYNC && s.isActive()) {
                asyncList.add(s);
            }
        }
        async = asyncList.toArray(new SubscriptionImpl[0]);
        byte[] next = new byte[EventKind.COUNT];
        SubscriptionImpl[][] sync = new SubscriptionImpl[EventKind.COUNT][];
        boolean[] on = tracked;
        for (EventKind k : EventKind.values()) {
            int o = k.ordinal();
            if (!on[o]) {
                continue;
            }
            byte w = 0;
            List<SubscriptionImpl> syncList = null;
            for (SubscriptionImpl s : subscriptions) {
                if (!s.isActive() || !s.wants(k)) {
                    continue;
                }
                if (s.delivery == Delivery.SYNC) {
                    w |= 2;
                    if (syncList == null) {
                        syncList = new ArrayList<>(2);
                    }
                    syncList.add(s);
                } else {
                    w |= 4;
                }
            }
            for (Sink sink : sinks) {
                if (sink.wants(k)) {
                    w |= 8;
                }
            }
            if (w != 0) {
                w |= 1;
            }
            next[o] = w;
            sync[o] = syncList == null ? null : syncList.toArray(new SubscriptionImpl[0]);
        }
        syncByKind = sync;
        wants = next;
    }

    // ---- queue and stats -------------------------------------------------------------------------------

    MpmcQueue<TrackerEvent> queue() {
        return queue;
    }

    public int queued() {
        return queue.size();
    }

    public int queueCapacity() {
        return queue.capacity();
    }

    public long published(EventKind kind) {
        return published[kind.ordinal()].sum();
    }

    public long dropped(EventKind kind) {
        return dropped[kind.ordinal()].sum();
    }

    public long seq() {
        return seq.get();
    }

    public void resetCounters() {
        for (int i = 0; i < EventKind.COUNT; i++) {
            published[i].reset();
            dropped[i].reset();
        }
    }

    /**
     * The first step of a stop. Nothing is published from now on, so sync listeners and sinks hear nothing
     * more, while the async subscriptions stay open for the dispatcher to drain what is queued. Then
     * {@link #close}.
     */
    public void stopAccepting() {
        closed = true;
        wants = new byte[EventKind.COUNT];
    }

    /** Stops publishing and drops every subscription and sink. Call it after the dispatcher drained. */
    public synchronized void close() {
        stopAccepting();
        for (SubscriptionImpl s : subscriptions) {
            s.deactivate();
        }
        subscriptions.clear();
        sinks.clear();
        async = new SubscriptionImpl[0];
        wants = new byte[EventKind.COUNT];
    }

    public boolean isClosed() {
        return closed;
    }
}
