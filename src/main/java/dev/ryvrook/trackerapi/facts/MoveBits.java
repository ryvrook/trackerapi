package dev.ryvrook.trackerapi.facts;

import dev.ryvrook.trackerapi.api.event.MovementEdge;

/**
 * The one encoding of the engine's movement state booleans, shared by the edge system and the sampler, and
 * the rule that turns two encodings into movement edges. Packing the engine object happens in the hooks,
 * this class only sees ints, so it is pure, allocation free and thread safe.
 */
public final class MoveBits {
    private MoveBits() {
    }

    public static final int IDLE = 1 << 0, JUMPING = 1 << 1, FLYING = 1 << 2, WALKING = 1 << 3, RUNNING = 1 << 4,
            SPRINTING = 1 << 5, CROUCHING = 1 << 6, FALLING = 1 << 7, FALLING_FAR = 1 << 8, CLIMBING = 1 << 9,
            IN_FLUID = 1 << 10, SWIMMING = 1 << 11, SWIM_JUMPING = 1 << 12, ON_GROUND = 1 << 13, MANTLING = 1 << 14,
            SLIDING = 1 << 15, MOUNTING = 1 << 16, ROLLING = 1 << 17, SITTING = 1 << 18, GLIDING = 1 << 19,
            SLEEPING = 1 << 20;

    /** Field names in bit order, for debug output and the layout test. */
    public static final String[] NAMES = {"idle", "jumping", "flying", "walking", "running", "sprinting",
            "crouching", "falling", "fallingFar", "climbing", "inFluid", "swimming", "swimJumping", "onGround",
            "mantling", "sliding", "mounting", "rolling", "sitting", "gliding", "sleeping"};

    private static final MovementEdge.Edge[] EDGES = MovementEdge.Edge.values();

    /** True when every bit of the mask is set. */
    public static boolean has(int bits, int mask) {
        return (bits & mask) == mask;
    }

    /** True when the mask went from unset to set between two samples. */
    public static boolean rose(int before, int after, int mask) {
        return (before & mask) == 0 && (after & mask) != 0;
    }

    /** True when the mask went from set to unset between two samples. */
    public static boolean fell(int before, int after, int mask) {
        return (before & mask) != 0 && (after & mask) == 0;
    }

    /** Packs the 21 booleans in the order of {@link #NAMES}. */
    public static int pack(boolean idle, boolean jumping, boolean flying, boolean walking, boolean running,
                           boolean sprinting, boolean crouching, boolean falling, boolean fallingFar,
                           boolean climbing, boolean inFluid, boolean swimming, boolean swimJumping,
                           boolean onGround, boolean mantling, boolean sliding, boolean mounting, boolean rolling,
                           boolean sitting, boolean gliding, boolean sleeping) {
        int b = 0;
        if (idle) b |= IDLE;
        if (jumping) b |= JUMPING;
        if (flying) b |= FLYING;
        if (walking) b |= WALKING;
        if (running) b |= RUNNING;
        if (sprinting) b |= SPRINTING;
        if (crouching) b |= CROUCHING;
        if (falling) b |= FALLING;
        if (fallingFar) b |= FALLING_FAR;
        if (climbing) b |= CLIMBING;
        if (inFluid) b |= IN_FLUID;
        if (swimming) b |= SWIMMING;
        if (swimJumping) b |= SWIM_JUMPING;
        if (onGround) b |= ON_GROUND;
        if (mantling) b |= MANTLING;
        if (sliding) b |= SLIDING;
        if (mounting) b |= MOUNTING;
        if (rolling) b |= ROLLING;
        if (sitting) b |= SITTING;
        if (gliding) b |= GLIDING;
        if (sleeping) b |= SLEEPING;
        return b;
    }

    /**
     * The edges between two encodings as a mask over {@link MovementEdge.Edge} ordinals, zero when nothing
     * worth an event changed. Walking, running and idle flip constantly and give no edge.
     */
    public static int edges(int before, int after) {
        int changed = before ^ after;
        if (changed == 0) {
            return 0;
        }
        int m = 0;
        if (rose(before, after, JUMPING)) m |= bit(MovementEdge.Edge.JUMP);
        if (rose(before, after, FALLING)) m |= bit(MovementEdge.Edge.FALL_START);
        if (rose(before, after, ON_GROUND)) m |= bit(MovementEdge.Edge.LANDED);
        if (rose(before, after, GLIDING)) m |= bit(MovementEdge.Edge.GLIDE_START);
        if (fell(before, after, GLIDING)) m |= bit(MovementEdge.Edge.GLIDE_END);
        if (rose(before, after, SWIMMING)) m |= bit(MovementEdge.Edge.SWIM_START);
        if (fell(before, after, SWIMMING)) m |= bit(MovementEdge.Edge.SWIM_END);
        if (rose(before, after, SPRINTING)) m |= bit(MovementEdge.Edge.SPRINT_START);
        if (fell(before, after, SPRINTING)) m |= bit(MovementEdge.Edge.SPRINT_END);
        if (rose(before, after, CROUCHING)) m |= bit(MovementEdge.Edge.CROUCH_START);
        if (fell(before, after, CROUCHING)) m |= bit(MovementEdge.Edge.CROUCH_END);
        if (rose(before, after, CLIMBING)) m |= bit(MovementEdge.Edge.CLIMB_START);
        if (fell(before, after, CLIMBING)) m |= bit(MovementEdge.Edge.CLIMB_END);
        if (rose(before, after, FALLING_FAR)) m |= bit(MovementEdge.Edge.FALL_FAR);
        return m;
    }

    /** The mask bit of one edge. */
    public static int bit(MovementEdge.Edge edge) {
        return 1 << edge.ordinal();
    }

    /** The edge of a mask bit index, see {@link #edges}. */
    public static MovementEdge.Edge edge(int ordinal) {
        return EDGES[ordinal];
    }
}
