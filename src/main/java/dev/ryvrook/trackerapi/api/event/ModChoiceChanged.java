package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player picked or changed a class, race, specialisation or similar. {@code slot} names which, {@code id} the
 * mod's id of the pick, {@code name} its display name or null, {@code tier} 0 when the mod has none.
 */
public record ModChoiceChanged(Header header, String mod, String slot, String id, String name, int tier) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_CHOICE_CHANGED;
    }
}
