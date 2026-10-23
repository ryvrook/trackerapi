package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * Experience of a mod was gained. {@code track} as in {@link ModLevelUp}, {@code source} the mod's name for where it
 * came from, such as a kill or a block, or null. High volume.
 */
public record ModXpGained(Header header, String mod, String track, double amount, String source) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_XP_GAINED;
    }
}
