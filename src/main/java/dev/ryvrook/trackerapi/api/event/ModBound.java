package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * TrackerAPI bound to a mod or let it go. A server event, the header carries no player. Consumers that show mod
 * statistics refresh their catalogue on it.
 */
public record ModBound(Header header, String mod, String pluginId, String version, boolean bound, java.util.List<String> features) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_BOUND;
    }
}
