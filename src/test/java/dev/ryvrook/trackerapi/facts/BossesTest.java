package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossesTest {

    @Test
    void namedBossesAndEveryGobliteratorPhase() {
        assertTrue(Bosses.isBoss("Dragon_Fire"));
        assertTrue(Bosses.isBoss("Goblin_Duke_Phase_3_Slow"));
        assertTrue(Bosses.isBoss("Hedera"));
        assertTrue(Bosses.isBoss("Goblin_Gobliterator_Phase1"));
        assertTrue(Bosses.isBoss("Goblin_Gobliterator_Transition_2"));
        assertTrue(Bosses.isBoss("Goblin_Gobliterator_SawTransition"));
        assertFalse(Bosses.isBoss("Goblin_Scrapper"));
        assertFalse(Bosses.isBoss("Goblin_Gobliterator_Difficulty_Button"));
        assertFalse(Bosses.isBoss(null));
    }

    @Test
    void onlyTheThirdPhaseIsTheFinalGobliterator() {
        assertTrue(Bosses.isGobliteratorFinal("Goblin_Gobliterator_Phase3"));
        assertTrue(Bosses.isGobliteratorFinal("Goblin_Gobliterator_Phase3_HM"));
        assertFalse(Bosses.isGobliteratorFinal("Goblin_Gobliterator_Phase2"));
        assertFalse(Bosses.isGobliteratorFinal(null));
        assertTrue(Bosses.isGobliteratorFinalHard("Goblin_Gobliterator_Phase3_HM"));
        assertFalse(Bosses.isGobliteratorFinalHard("Goblin_Gobliterator_Phase3"));
    }
}
