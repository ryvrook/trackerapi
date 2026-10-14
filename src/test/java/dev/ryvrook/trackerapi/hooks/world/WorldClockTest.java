package dev.ryvrook.trackerapi.hooks.world;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldClockTest {

    @Test
    void firstWeatherIsABaseline() {
        WorldClock clock = new WorldClock();
        assertFalse(clock.weather("Zone1_Sunny"));
        assertEquals("Zone1_Sunny", clock.currentWeather());
        assertNull(clock.previousWeather());
    }

    @Test
    void sameWeatherPublishesNothing() {
        WorldClock clock = new WorldClock();
        clock.weather("Zone1_Sunny");
        assertFalse(clock.weather("Zone1_Sunny"));
        assertFalse(clock.weather("Zone1_Sunny"));
    }

    @Test
    void changeKeepsThePreviousId() {
        WorldClock clock = new WorldClock();
        clock.weather("Zone1_Sunny");
        assertTrue(clock.weather("Zone1_Rain"));
        assertEquals("Zone1_Sunny", clock.previousWeather());
        assertEquals("Zone1_Rain", clock.currentWeather());
        assertTrue(clock.weather("Zone1_Storm"));
        assertEquals("Zone1_Rain", clock.previousWeather());
    }

    @Test
    void noWeatherCountsAsAValue() {
        WorldClock clock = new WorldClock();
        clock.weather(null);
        assertTrue(clock.weather("Zone1_Rain"));
        assertNull(clock.previousWeather());
        assertTrue(clock.weather(null));
        assertEquals("Zone1_Rain", clock.previousWeather());
        assertFalse(clock.weather(null));
    }

    @Test
    void forgettingStartsANewBaseline() {
        WorldClock clock = new WorldClock();
        clock.weather("Zone1_Sunny");
        clock.forgetWeather();
        assertFalse(clock.weather("Zone1_Rain"));
        assertNull(clock.previousWeather());
        assertTrue(clock.weather("Zone1_Sunny"));
    }

    @Test
    void timeIsDueOnceAMinute() {
        WorldClock clock = new WorldClock();
        long t0 = 5_000_000_000L;
        assertTrue(clock.timeDue(t0));
        assertFalse(clock.timeDue(t0 + 1));
        assertFalse(clock.timeDue(t0 + WorldClock.TIME_EVERY_NANOS - 1));
        assertTrue(clock.timeDue(t0 + WorldClock.TIME_EVERY_NANOS));
        assertFalse(clock.timeDue(t0 + WorldClock.TIME_EVERY_NANOS + 1_000));
        assertTrue(clock.timeDue(t0 + 3 * WorldClock.TIME_EVERY_NANOS));
    }

    @Test
    void timeIsDueAtOnceWithNegativeNanos() {
        WorldClock clock = new WorldClock();
        assertTrue(clock.timeDue(Long.MIN_VALUE + 10));
        assertFalse(clock.timeDue(Long.MIN_VALUE + 20));
    }

    @Test
    void forcedWeatherWins() {
        assertEquals(7, WorldClock.chooseWeatherIndex(7, true, 3, true, 2));
        assertEquals(7, WorldClock.chooseWeatherIndex(7, false, 0, false, 0));
    }

    @Test
    void environmentZeroBeforeTheLowest() {
        assertEquals(3, WorldClock.chooseWeatherIndex(0, true, 3, true, 2));
        assertEquals(0, WorldClock.chooseWeatherIndex(0, true, 0, true, 2));
    }

    @Test
    void lowestEnvironmentLast() {
        assertEquals(2, WorldClock.chooseWeatherIndex(0, false, 0, true, 2));
        assertEquals(0, WorldClock.chooseWeatherIndex(0, false, 0, false, 0));
    }

    @Test
    void gameDayCountsFromYearZero() {
        LocalDate zero = LocalDate.of(1, 1, 1);
        assertEquals(0, WorldClock.gameDay(zero, zero));
        assertEquals(1, WorldClock.gameDay(LocalDate.of(1, 1, 2), zero));
        assertEquals(365, WorldClock.gameDay(LocalDate.of(2, 1, 1), zero));
        assertEquals(-1, WorldClock.gameDay(null, zero));
        assertEquals(-1, WorldClock.gameDay(zero, null));
    }
}
