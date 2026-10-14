package dev.ryvrook.trackerapi.api;

/**
 * Where a subscriber's listener runs.
 *
 * <p>{@link #ASYNC} is the default and the right choice for nearly everything. Events are queued on the
 * thread that observed them and delivered a few milliseconds later on the single {@code TrackerAPI-dispatch}
 * thread, in order, in batches. The listener may take its time, hold locks and do file work, within the
 * budget TrackerAPI gives each listener per batch (see the {@code listenerBudgetMillis} setting). A listener
 * that stays over its budget for twenty batches in a row is suspended for five minutes with one warning.
 *
 * <p>{@link #SYNC} runs the listener on the thread that observed the event, which for most kinds is the
 * world thread inside the engine tick. It exists for consumers that must read engine state in the same tick,
 * such as the inventory of a player who just killed a boss. The listener must return in microseconds, must
 * not block and must not touch other worlds. Its cost is measured and shown by {@code /trackerapi perf}, and
 * a listener that averages over 200 microseconds per call is suspended with one warning.
 */
public enum Delivery {
    ASYNC,
    SYNC
}
