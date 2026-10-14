package dev.ryvrook.trackerapi.bus;

import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One listener and its counters. {@code kinds} is null for a subscription to everything. The listener is
 * called through {@link #deliver}, which never throws, counts failures and updates the per batch budget
 * bookkeeping the dispatcher and the sync path use to suspend a slow listener.
 */
final class SubscriptionImpl implements Subscription {
    final String owner;
    final Set<EventKind> kinds;
    final Delivery delivery;
    private final Consumer<TrackerEvent> listener;
    private final Runnable onClose;
    private volatile boolean active = true;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    /** Sync path, running mean cost in nanos over the last calls, read by the publisher to suspend. */
    volatile long syncNanosTotal;
    volatile long syncCalls;
    /** Set when the listener was suspended, in nanos, 0 while it runs. */
    volatile long suspendedUntil;
    /** Async path, how many batches in a row went over budget. */
    int overBudget;

    SubscriptionImpl(String owner, Set<EventKind> kinds, Delivery delivery, Consumer<TrackerEvent> listener, Runnable onClose) {
        this.owner = owner;
        this.kinds = kinds;
        this.delivery = delivery;
        this.listener = listener;
        this.onClose = onClose;
    }

    boolean wants(EventKind kind) {
        return kinds == null || kinds.contains(kind);
    }

    /** Calls the listener. True when it threw. */
    boolean deliver(TrackerEvent event) {
        try {
            listener.accept(event);
            delivered.incrementAndGet();
            return false;
        } catch (RuntimeException | LinkageError e) {
            failures.incrementAndGet();
            lastFailure = e;
            return true;
        }
    }

    volatile Throwable lastFailure;

    @Override
    public EventKind kind() {
        return kinds != null && kinds.size() == 1 ? kinds.iterator().next() : null;
    }

    @Override
    public Delivery delivery() {
        return delivery;
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public long delivered() {
        return delivered.get();
    }

    @Override
    public long failures() {
        return failures.get();
    }

    @Override
    public void close() {
        if (active) {
            active = false;
            onClose.run();
        }
    }

    /** Called by the bus at shutdown, drops the listener without running the close hook twice. */
    void deactivate() {
        active = false;
    }

    @Override
    public String toString() {
        return owner + " " + (kinds == null ? "all" : kinds.size() == 1 ? kinds.iterator().next().id() : kinds.size() + " kinds")
                + " " + delivery.name().toLowerCase(java.util.Locale.ROOT);
    }
}
