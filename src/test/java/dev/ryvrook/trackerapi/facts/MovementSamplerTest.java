package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementSamplerTest {
    private final UUID p = UUID.randomUUID();

    @Test
    void firstSampleOnlySetsTheStart() {
        MovementSampler sampler = new MovementSampler();
        assertSame(MovementSampler.Step.NONE, sampler.sample(p, 0, 64, 0, MovementSampler.Mode.WALK));
        assertEquals(5, sampler.sample(p, 5, 64, 0, MovementSampler.Mode.WALK).metres());
    }

    @Test
    void jitterAndTeleportsDoNotCountButMoveTheStart() {
        MovementSampler sampler = new MovementSampler();
        sampler.sample(p, 0, 64, 0, null);
        assertSame(MovementSampler.Step.NONE, sampler.sample(p, 0.2, 64, 0, null));
        assertSame(MovementSampler.Step.NONE, sampler.sample(p, 500, 64, 0, null)); // a teleport
        assertEquals(3, sampler.sample(p, 503, 64, 0, null).metres());
    }

    @Test
    void fractionsCarryOver() {
        MovementSampler sampler = new MovementSampler();
        sampler.sample(p, 0, 0, 0, null);
        long total = 0;
        for (int i = 1; i <= 10; i++) {
            total += sampler.sample(p, i * 0.6, 0, 0, null).metres();
        }
        assertEquals(6, total); // 10 steps of 0.6 m
    }

    @Test
    void swimAndGlideHaveTheirOwnShare() {
        MovementSampler sampler = new MovementSampler();
        sampler.sample(p, 0, 64, 0, MovementSampler.Mode.WALK);
        MovementSampler.Step swim = sampler.sample(p, 4.5, 64, 0, MovementSampler.Mode.SWIM);
        assertEquals(4, swim.metres());
        assertEquals(MovementSampler.Mode.SWIM, swim.mode());
        assertEquals(4, swim.modeMetres());
        MovementSampler.Step walk = sampler.sample(p, 6, 64, 0, MovementSampler.Mode.WALK);
        assertEquals(2, walk.metres()); // 0.5 carried plus 1.5
        assertEquals(0, walk.modeMetres());
        MovementSampler.Step swimAgain = sampler.sample(p, 6.6, 64, 0, MovementSampler.Mode.SWIM);
        assertEquals(1, swimAgain.modeMetres()); // 0.5 carried in the swim share plus 0.6
        MovementSampler.Step glide = sampler.sample(p, 6.6, 54, 30, MovementSampler.Mode.GLIDE);
        assertEquals(31, glide.modeMetres());
    }

    @Test
    void resetPositionStartsAfreshAndForgetDropsEverything() {
        MovementSampler sampler = new MovementSampler();
        sampler.sample(p, 0, 64, 0, null);
        sampler.resetPosition(p);
        assertSame(MovementSampler.Step.NONE, sampler.sample(p, 30, 64, 0, null));
        sampler.forget(p);
        assertSame(MovementSampler.Step.NONE, sampler.sample(p, 40, 64, 0, null));
        assertSame(MovementSampler.Step.NONE, sampler.sample(null, 40, 64, 0, null));
    }

    @Test
    void aSmallStepMovesWithoutAWholeMetre() {
        MovementSampler sampler = new MovementSampler();
        sampler.sample(p, 0, 64, 0, MovementSampler.Mode.WALK);
        MovementSampler.Step small = sampler.sample(p, 0.5, 64, 0, MovementSampler.Mode.WALK);
        assertTrue(small.moved());
        assertEquals(0, small.metres());
        assertSame(small, MovementSampler.Step.small(MovementSampler.Mode.WALK)); // shared, no allocation
        assertFalse(MovementSampler.Step.NONE.moved());
        assertEquals(1, sampler.sample(p, 1.0, 64, 0, MovementSampler.Mode.WALK).metres());
    }

    @Test
    void ridingCarriesItsOwnShare() {
        MovementSampler rides = new MovementSampler();
        rides.sample(p, 0, 64, 0, MovementSampler.Mode.RIDE);
        MovementSampler.Step a = rides.sample(p, 10.4, 64, 0, MovementSampler.Mode.RIDE);
        assertEquals(10, a.metres());
        assertEquals(10, a.modeMetres());
        assertEquals(MovementSampler.Mode.RIDE, a.mode());
        MovementSampler.Step b = rides.sample(p, 11.0, 64, 0, MovementSampler.Mode.RIDE);
        assertEquals(1, b.modeMetres()); // 0.4 carried plus 0.6
        assertEquals(1, rides.size());
    }

    @Test
    void stepsAtTheBoundsCount() {
        MovementSampler sampler = new MovementSampler();
        sampler.sample(p, 0, 0, 0, null);
        assertTrue(sampler.sample(p, MovementSampler.MIN_STEP, 0, 0, null).moved());
        double x = MovementSampler.MIN_STEP + MovementSampler.MAX_STEP;
        assertEquals(64, sampler.sample(p, x, 0, 0, null).metres());
        assertSame(MovementSampler.Step.NONE, sampler.sample(p, x + MovementSampler.MAX_STEP + 0.01, 0, 0, null));
    }
}
