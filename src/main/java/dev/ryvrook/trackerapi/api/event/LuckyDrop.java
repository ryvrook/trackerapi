package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A mining luck mod granted an extra drop. {@code streak} is the streak that earned it, 0 when the mod does not say.
 */
public record LuckyDrop(Header header, String mod, String blockId, String itemId, int streak) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.LUCKY_DROP;
    }
}
