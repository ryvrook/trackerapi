package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A one time progress mark of a mod: a quest, an achievement, a bounty, a bestiary entry, a rune, an unlocked skill or a
 * world event joined. {@code category} is the lower case singular such as {@code quest}, {@code id} the mod's id of it.
 */
public record ModMilestone(Header header, String mod, String category, String id, String name) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_MILESTONE;
    }
}
