package dev.ryvrook.trackerapi.facts;

import dev.ryvrook.trackerapi.api.event.MovementEdge.Edge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveBitsTest {

    private static List<Edge> edges(int before, int after) {
        List<Edge> out = new ArrayList<>();
        for (int m = MoveBits.edges(before, after); m != 0; m &= m - 1) {
            out.add(MoveBits.edge(Integer.numberOfTrailingZeros(m)));
        }
        return out;
    }

    @Test
    void layoutFollowsTheNames() {
        assertEquals(21, MoveBits.NAMES.length);
        boolean[] f = new boolean[21];
        for (int i = 0; i < 21; i++) {
            java.util.Arrays.fill(f, false);
            f[i] = true;
            int bits = MoveBits.pack(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11], f[12],
                    f[13], f[14], f[15], f[16], f[17], f[18], f[19], f[20]);
            assertEquals(1 << i, bits, MoveBits.NAMES[i]);
        }
        assertEquals(MoveBits.SLEEPING, 1 << 20);
        assertEquals(MoveBits.ON_GROUND, 1 << 13);
    }

    @Test
    void noChangeNoEdge() {
        assertEquals(0, MoveBits.edges(MoveBits.ON_GROUND, MoveBits.ON_GROUND));
        assertEquals(0, MoveBits.edges(0, 0));
    }

    @Test
    void walkingRunningAndIdleGiveNoEdge() {
        int ground = MoveBits.ON_GROUND;
        assertEquals(0, MoveBits.edges(ground | MoveBits.IDLE, ground | MoveBits.WALKING));
        assertEquals(0, MoveBits.edges(ground | MoveBits.WALKING, ground | MoveBits.RUNNING));
        assertEquals(0, MoveBits.edges(ground, ground | MoveBits.IN_FLUID));
    }

    @Test
    void aFarFallRisesOnce() {
        assertEquals(List.of(Edge.FALL_FAR), edges(MoveBits.FALLING, MoveBits.FALLING | MoveBits.FALLING_FAR));
        assertEquals(List.of(), edges(MoveBits.FALLING | MoveBits.FALLING_FAR, MoveBits.FALLING | MoveBits.FALLING_FAR));
        assertEquals(List.of(Edge.LANDED), edges(MoveBits.FALLING | MoveBits.FALLING_FAR, MoveBits.ON_GROUND),
                "the far flag dropping on landing is no edge of its own");
    }

    @Test
    void aJumpAndItsLanding() {
        int ground = MoveBits.ON_GROUND;
        assertEquals(List.of(Edge.JUMP), edges(ground, MoveBits.JUMPING));
        assertEquals(List.of(Edge.FALL_START), edges(MoveBits.JUMPING, MoveBits.FALLING));
        assertEquals(List.of(Edge.LANDED), edges(MoveBits.FALLING, ground));
    }

    @Test
    void startsAndEndsPair() {
        assertEquals(List.of(Edge.GLIDE_START), edges(MoveBits.FALLING, MoveBits.GLIDING));
        assertEquals(List.of(Edge.GLIDE_END), edges(MoveBits.GLIDING, 0));
        assertEquals(List.of(Edge.SWIM_START), edges(MoveBits.IN_FLUID, MoveBits.IN_FLUID | MoveBits.SWIMMING));
        assertEquals(List.of(Edge.SWIM_END), edges(MoveBits.SWIMMING, 0));
        assertEquals(List.of(Edge.SPRINT_START), edges(0, MoveBits.SPRINTING));
        assertEquals(List.of(Edge.SPRINT_END), edges(MoveBits.SPRINTING, 0));
        assertEquals(List.of(Edge.CROUCH_START), edges(0, MoveBits.CROUCHING));
        assertEquals(List.of(Edge.CROUCH_END), edges(MoveBits.CROUCHING, 0));
        assertEquals(List.of(Edge.CLIMB_START), edges(0, MoveBits.CLIMBING));
        assertEquals(List.of(Edge.CLIMB_END), edges(MoveBits.CLIMBING, 0));
    }

    @Test
    void severalEdgesAtOnceComeInEdgeOrder() {
        int before = MoveBits.ON_GROUND | MoveBits.SPRINTING;
        int after = MoveBits.JUMPING | MoveBits.CROUCHING;
        assertEquals(List.of(Edge.JUMP, Edge.SPRINT_END, Edge.CROUCH_START), edges(before, after));
    }

    @Test
    void roseAndFell() {
        assertTrue(MoveBits.rose(0, MoveBits.JUMPING, MoveBits.JUMPING));
        assertFalse(MoveBits.rose(MoveBits.JUMPING, MoveBits.JUMPING, MoveBits.JUMPING));
        assertTrue(MoveBits.fell(MoveBits.GLIDING, 0, MoveBits.GLIDING));
        assertFalse(MoveBits.fell(0, 0, MoveBits.GLIDING));
        assertTrue(MoveBits.has(MoveBits.GLIDING | MoveBits.FALLING, MoveBits.GLIDING));
    }

    @Test
    void everyEdgeHasOneBit() {
        for (Edge e : Edge.values()) {
            assertEquals(e, MoveBits.edge(Integer.numberOfTrailingZeros(MoveBits.bit(e))));
        }
    }
}
