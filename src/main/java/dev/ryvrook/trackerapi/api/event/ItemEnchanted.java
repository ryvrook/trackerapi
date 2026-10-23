package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A player put an enchantment on an item through a mod.
 */
public record ItemEnchanted(Header header, String mod, String itemId, String enchantId, int level) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ITEM_ENCHANTED;
    }
}
