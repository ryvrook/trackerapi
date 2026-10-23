package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A combat or movement move of a mod, such as {@code perfect_parry}, {@code counterattack}, {@code wall_jump} or
 * {@code grapple}. {@code pvp} is true against a player, {@code value} a size the mod gives, such as damage or metres, or 0.
 */
public record ModAction(Header header, String mod, String action, boolean pvp, String targetTypeId, double value) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_ACTION;
    }
}
