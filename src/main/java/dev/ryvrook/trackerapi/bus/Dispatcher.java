package dev.ryvrook.trackerapi.bus;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.util.Guard;
import dev.ryvrook.trackerapi.util.Perf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;

/**
 * The {@code TrackerAPI-dispatch} thread. Polls the async queue, gathers up to {@link #BATCH} events and
 * hands the batch to every async listener in subscription order, each within its budget. While events
 * flow the thread looks at the queue every millisecond, so a busy server gets its events within a
 * millisecond or two and its world threads never pay for waking this one. After 50 empty looks it parks
 * and the next publish unparks it, so an idle server costs nothing.
 *
 * <p>A listener that stays over its budget for {@link #SUSPEND_AFTER} batches in a row is suspended for
 * five minutes with one warning, as the Chronicle change bus does, so one slow consumer cannot hold up
 * the others.
 */
public final class Dispatcher implements Runnable {
    static final int BATCH = 512;
    static final int SUSPEND_AFTER = 20;
    static final long SUSPEND_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** After a batch the thread naps this long between looks, without asking publishers to wake it. */
    private static final long NAP_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    /** Empty looks in a row before the thread parks for real and publishers wake it again. */
    private static final int NAPS = 50;

    private final Publisher publisher;
    private final HytaleLogger logger;
    private final Perf perf;
    private final Guard.RateLimiter warnings = new Guard.RateLimiter(TimeUnit.MINUTES.toNanos(1));
    private volatile long budgetNanos = TimeUnit.MILLISECONDS.toNanos(5);
    private volatile boolean stop;
    private volatile Thread thread;
    /** True while the thread is parked with nothing to do, the only time a publish pays for an unpark. */
    private volatile boolean idle;
    private final List<TrackerEvent> batch = new ArrayList<>(BATCH);

    public Dispatcher(Publisher publisher, HytaleLogger logger, Perf perf) {
        this.publisher = publisher;
        this.logger = logger;
        this.perf = perf;
    }

    public void setBudgetMillis(int millis) {
        budgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
    }

    public void start() {
        Thread t = new Thread(this, "TrackerAPI-dispatch");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, e) -> logger.at(Level.SEVERE).withCause(e).log("TrackerAPI dispatch thread died"));
        thread = t;
        t.start();
    }

    /**
     * Called by the publisher after an offer. One volatile read unless the thread is idle. The offer and the
     * read of {@code idle} here, and the write of {@code idle} and the second look at the queue in
     * {@link #run}, are all volatile, so either the thread sees the event or this call sees it idle.
     */
    public void wake() {
        if (!idle) {
            return;
        }
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** Asks the thread to drain what is queued and stop. Waits up to {@code millis}. */
    public void stop(long millis) {
        stop = true;
        Thread t = thread;
        if (t == null) {
            return;
        }
        LockSupport.unpark(t);
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    @Override
    public void run() {
        MpmcQueue<TrackerEvent> queue = publisher.queue();
        int naps = NAPS;
        while (true) {
            TrackerEvent first = queue.poll();
            if (first == null) {
                if (stop) {
                    return;
                }
                if (naps < NAPS) {
                    // Busy a moment ago. Nap and look again, so a steady stream is gathered into batches and
                    // no world thread pays for waking this one per event.
                    naps++;
                    LockSupport.parkNanos(this, NAP_NANOS);
                    continue;
                }
                idle = true;
                if (queue.isEmpty() && !stop) {
                    LockSupport.parkNanos(this, PARK_NANOS);
                }
                idle = false;
                continue;
            }
            naps = 0;
            batch.add(first);
            TrackerEvent e;
            while (batch.size() < BATCH && (e = queue.poll()) != null) {
                batch.add(e);
            }
            long start = System.nanoTime();
            try {
                deliver(batch);
            } catch (RuntimeException | LinkageError ex) {
                Guard.failed(ex, "TrackerAPI dispatch failed");
            } finally {
                batch.clear();
                perf.record(Perf.R_DISPATCH, System.nanoTime() - start);
            }
        }
    }

    private void deliver(List<TrackerEvent> events) {
        List<SubscriptionImpl> listeners = publisher.asyncSubscriptions();
        long now = System.nanoTime();
        for (SubscriptionImpl s : listeners) {
            if (s.suspendedUntil != 0) {
                if (now < s.suspendedUntil) {
                    continue;
                }
                s.suspendedUntil = 0;
                s.overBudget = 0;
            }
            long begin = System.nanoTime();
            boolean failedOnce = false;
            for (int i = 0, n = events.size(); i < n; i++) {
                TrackerEvent ev = events.get(i);
                if (!s.isActive()) {
                    break;
                }
                if (s.wants(ev.kind()) && s.deliver(ev)) {
                    failedOnce = true;
                }
            }
            if (failedOnce && warnings.allow("fail:" + s.owner)) {
                logger.at(Level.WARNING).withCause(s.lastFailure).log("TrackerAPI listener of %s failed", s.owner);
            }
            long took = System.nanoTime() - begin;
            if (took > budgetNanos) {
                s.overBudget++;
                if (s.overBudget >= SUSPEND_AFTER) {
                    s.overBudget = 0;
                    s.suspendedUntil = System.nanoTime() + SUSPEND_NANOS;
                    logger.at(Level.WARNING).log("TrackerAPI suspended the listener of %s for 5 minutes because it ran over its budget in %d batches in a row",
                            s.owner, SUSPEND_AFTER);
                } else if (warnings.allow("budget:" + s.owner)) {
                    logger.at(Level.WARNING).log("TrackerAPI listener of %s took %d ms for one batch, over its budget of %d ms",
                            s.owner, TimeUnit.NANOSECONDS.toMillis(took), TimeUnit.NANOSECONDS.toMillis(budgetNanos));
                }
            } else {
                s.overBudget = 0;
            }
        }
    }
}
