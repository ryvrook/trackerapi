package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A level of a mod rose. {@code mod} is the binding id such as {@code rpg}, {@code track} is {@code player} for the
 * main level or the id of a skill or job that has its own level. {@code maxLevel} is 0 when the mod has no cap.
 */
public record ModLevelUp(Header header, String mod, String track, int oldLevel, int newLevel, int maxLevel) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_LEVEL_UP;
    }
}
