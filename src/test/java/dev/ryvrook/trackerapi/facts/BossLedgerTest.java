package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossLedgerTest {
    private static final long SECOND = 1_000_000_000L;
    private final UUID boss = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void untouchedOnlyWithoutDamageDuringTheFight() {
        BossLedger ledger = new BossLedger();
        ledger.hurt(alice, 0); // before the fight opened, does not count
        ledger.record(boss, alice, SECOND);
        ledger.record(boss, bob, 2 * SECOND);
        ledger.hurt(bob, 3 * SECOND);
        BossLedger.Outcome fight = ledger.finish(boss, 4 * SECOND);
        assertEquals(List.of(alice, bob), fight.credited());
        assertTrue(fight.untouched(alice));
        assertFalse(fight.untouched(bob));
        assertEquals(0, ledger.size());
    }

    @Test
    void damageBeforeYourFirstHitStillCounts() {
        BossLedger ledger = new BossLedger();
        ledger.record(boss, alice, SECOND);
        ledger.hurt(bob, 2 * SECOND); // bob is hit before he joins in
        ledger.record(boss, bob, 3 * SECOND);
        BossLedger.Outcome fight = ledger.finish(boss, 4 * SECOND);
        assertFalse(fight.untouched(bob));
        assertTrue(fight.untouched(alice));
    }

    @Test
    void unknownBossIsNeverUntouched() {
        BossLedger ledger = new BossLedger();
        BossLedger.Outcome fight = ledger.finish(boss, SECOND);
        assertFalse(fight.known());
        assertFalse(fight.untouched(alice));
        assertEquals(List.of(), ledger.take(boss, SECOND));
    }

    @Test
    void aFullHurtListOnlyCostsPlayersWhoJoinAfterIt() {
        BossLedger ledger = new BossLedger();
        UUID late = UUID.randomUUID();
        ledger.record(boss, alice, "arena", SECOND);
        for (int i = 0; i <= BossLedger.MAX_HURT; i++) {
            ledger.hurt(UUID.randomUUID(), "arena", 2 * SECOND);
        }
        ledger.hurt(bob, "arena", 2 * SECOND); // no room left for bob, who has not hit yet
        ledger.record(boss, bob, "arena", 3 * SECOND);
        ledger.record(boss, late, "arena", 3 * SECOND);
        ledger.hurt(alice, "arena", 4 * SECOND); // alice has hit, so her marks are always kept
        BossLedger.Outcome fight = ledger.finish(boss, 5 * SECOND);
        assertFalse(fight.untouched(alice));
        assertFalse(fight.untouched(bob), "bob may have been hurt unnoticed");
        assertFalse(fight.untouched(late), "late joined after the list filled up");
        assertFalse(fight.untouched(UUID.randomUUID()), "a one blow killer cannot be judged either");
    }

    @Test
    void damageWhileTheFightIsQuietStillCounts() {
        BossLedger ledger = new BossLedger();
        ledger.record(boss, bob, SECOND); // bob hits, backs off
        ledger.hurt(bob, SECOND + BossLedger.WINDOW_NANOS + SECOND); // and takes damage while nobody fights
        ledger.record(boss, bob, SECOND + BossLedger.WINDOW_NANOS + 30 * SECOND); // then comes back
        BossLedger.Outcome fight = ledger.finish(boss, SECOND + BossLedger.WINDOW_NANOS + 31 * SECOND);
        assertEquals(List.of(bob), fight.credited());
        assertFalse(fight.untouched(bob));
    }

    @Test
    void oldHitsAreNotCreditedButStillJudged() {
        BossLedger ledger = new BossLedger();
        ledger.record(boss, alice, SECOND);
        ledger.record(boss, bob, SECOND + BossLedger.WINDOW_NANOS + 2 * SECOND);
        BossLedger.Outcome fight = ledger.finish(boss, SECOND + BossLedger.WINDOW_NANOS + 3 * SECOND);
        assertEquals(List.of(bob), fight.credited());
        assertTrue(fight.untouched(bob));
    }

    @Test
    void damageInAnotherWorldDoesNotTaint() {
        BossLedger ledger = new BossLedger();
        ledger.record(boss, alice, "deepworks-1", SECOND);
        ledger.hurt(alice, "default", 2 * SECOND);
        ledger.hurt(bob, "default", 2 * SECOND);
        ledger.record(boss, bob, "deepworks-1", 3 * SECOND);
        BossLedger.Outcome fight = ledger.finish(boss, 4 * SECOND);
        assertTrue(fight.untouched(alice));
        assertTrue(fight.untouched(bob));
    }

    @Test
    void forgottenFightsStartAfresh() {
        BossLedger ledger = new BossLedger();
        UUID other = UUID.randomUUID();
        ledger.record(boss, alice, SECOND);
        ledger.hurt(alice, 2 * SECOND);
        ledger.record(other, bob, 3 * SECOND + BossLedger.FORGET_NANOS); // a new boss prunes the old fight
        assertEquals(1, ledger.size());
        ledger.record(boss, alice, 4 * SECOND + BossLedger.FORGET_NANOS);
        assertTrue(ledger.finish(boss, 5 * SECOND + BossLedger.FORGET_NANOS).untouched(alice));
    }

    @Test
    void takeStillReturnsTheCreditedPlayers() {
        BossLedger ledger = new BossLedger();
        ledger.record(boss, alice, SECOND);
        assertEquals(List.of(alice), ledger.take(boss, 2 * SECOND));
    }

    @Test
    void aRemovedWorldDropsItsFights() {
        BossLedger ledger = new BossLedger();
        UUID other = UUID.randomUUID();
        ledger.record(boss, alice, "deepworks-1", SECOND);
        ledger.record(other, bob, "default", SECOND);
        ledger.forgetWorld("deepworks-1");
        assertEquals(1, ledger.size());
        assertFalse(ledger.finish(boss, 2 * SECOND).known());
        assertTrue(ledger.finish(other, 2 * SECOND).known());
    }
}
