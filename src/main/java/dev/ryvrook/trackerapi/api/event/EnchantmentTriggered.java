package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * An enchantment of a mod fired its effect. High volume, counted and not journaled by default.
 */
public record EnchantmentTriggered(Header header, String mod, String enchantId, String itemId) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.ENCHANTMENT_TRIGGERED;
    }
}
