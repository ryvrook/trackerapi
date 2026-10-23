package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A tamed companion of a mod changed. {@code companionId} is the mod's id of the animal or null, {@code level} its
 * level after the change or 0.
 */
public record CompanionChanged(Header header, String mod, String companionId, String npcTypeId, Change change, int level) implements TrackerEvent {

    public enum Change { TAMED, BRED, LEVEL_UP, DIED, RENAMED, MOUNTED, RELEASED }

    @Override
    public EventKind kind() {
        return EventKind.COMPANION_CHANGED;
    }
}
