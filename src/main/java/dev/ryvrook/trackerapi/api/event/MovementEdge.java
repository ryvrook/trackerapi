package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A change in a player's movement state. {@code y} is the player's height at the edge, {@code fallDistance} the blocks fallen for LANDED, else zero. FALL_FAR is the moment a fall becomes a long one in the engine's own movement states.
 *
 * <p>Immutable. Components are only ever added, so read through the accessors.
 */
public record MovementEdge(Header header, Edge edge, double y, double fallDistance) implements TrackerEvent {

    public enum Edge { JUMP, FALL_START, LANDED, GLIDE_START, GLIDE_END, SWIM_START, SWIM_END, SPRINT_START, SPRINT_END, CROUCH_START, CROUCH_END, CLIMB_START, CLIMB_END, FALL_FAR }

    @Override
    public EventKind kind() {
        return EventKind.MOVEMENT_EDGE;
    }
}
