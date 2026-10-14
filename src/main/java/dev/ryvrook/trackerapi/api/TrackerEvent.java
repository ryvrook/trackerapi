package dev.ryvrook.trackerapi.api;

/**
 * One thing that happened on the server, as TrackerAPI saw it. Every event is an immutable record under
 * {@link dev.ryvrook.trackerapi.api.event} and carries the wall clock time it was observed, a sequence number
 * that rises in the order events were published and the name of the world it happened in, or null for
 * server wide events.
 *
 * <p>Events are built once, on the thread that observed them, and shared with every subscriber and with the
 * journal. They hold plain values only, never engine refs, stores or components, so a subscriber may keep
 * them for as long as it likes and read them from any thread.
 *
 * <p>This interface is part of the stable TrackerAPI contract. Every implementation is a record under the
 * event package, one per {@link EventKind}, and records only ever gain components, so read them through
 * their accessors rather than record patterns. Switch on {@link #kind()} or with a type pattern and keep a
 * default branch, since kinds are added over time.
 */
public interface TrackerEvent {

    /** When, in what order, where and about whom. */
    Header header();

    /** The kind of this event, one constant per record type. */
    EventKind kind();

    /** When the event was observed, in milliseconds since the epoch. */
    default long at() {
        return header().at();
    }

    /**
     * Rises by one for every event published since the plugin started, across every kind and world. Two
     * events observed on the same world thread keep their order. Events from different worlds are ordered by
     * when each reached the publisher.
     */
    default long seq() {
        return header().seq();
    }

    /** The world the event happened in, or null for events that belong to no world. */
    default String world() {
        return header().world();
    }

    /**
     * The player the event is about, or null when no player is involved. For a death this is the victim when
     * the victim is a player, else the killer when the killer is a player. For damage it is the attacker when
     * the attacker is a player, else the victim.
     */
    default java.util.UUID player() {
        return header().player();
    }
}
