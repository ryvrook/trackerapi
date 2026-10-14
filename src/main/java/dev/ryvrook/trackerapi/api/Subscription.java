package dev.ryvrook.trackerapi.api;

/**
 * A live subscription. Call {@link #close()} to stop receiving events. Closing twice is harmless. Every
 * subscription a plugin holds is closed for it when TrackerAPI stops, so a consumer must subscribe again
 * from its {@link TrackerApi#onEnable} hook rather than keep this object across a reload.
 */
public interface Subscription extends AutoCloseable {

    /** The kind this subscription receives, or null for a subscription to every kind. */
    EventKind kind();

    Delivery delivery();

    /** The name of the plugin that subscribed, as given at subscribe time. */
    String owner();

    /** True until {@link #close()} or until TrackerAPI stopped. */
    boolean isActive();

    /** How many events were delivered to this listener so far. */
    long delivered();

    /** How many calls threw. Each failure is logged at most once a minute per listener. */
    long failures();

    /** True while the listener is suspended for running over its budget. */
    default boolean isSuspended() {
        return false;
    }

    @Override
    void close();
}
