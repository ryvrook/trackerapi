package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A mining luck mod granted an extra drop. {@code streak} is the streak that earned it, 0 when the mod does not say.
 * {@code creative} is the flag of the break that earned the drop, so the counters skip it as they skip creative breaks.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record LuckyDrop(Header header, String mod, String blockId, String itemId, int streak, boolean creative) implements TrackerEvent {

    /** The record without {@code creative}, which is then false. */
    public LuckyDrop(Header header, String mod, String blockId, String itemId, int streak) {
        this(header, mod, blockId, itemId, streak, false);
    }

    @Override
    public EventKind kind() {
        return EventKind.LUCKY_DROP;
    }
}
