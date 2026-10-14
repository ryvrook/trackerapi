package dev.ryvrook.trackerapi.hooks.world;

import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * What the once a second clock poll remembers about one world, and the pure rules it applies. The first
 * weather it sees is published with no previous weather, so a consumer learns the current weather after a
 * start, later reads publish only when the id differs. The
 * game time is due once a minute per world, the first read of a world is due at once.
 *
 * <p>The poll task of a world runs on that world's thread, the timer may forget the weather from its own
 * thread, so every method is synchronized. Nobody contends for it, it runs once a second per world.
 */
public final class WorldClock {
    /** How often a world's game time is published. */
    public static final long TIME_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private boolean weatherSeen;
    private String weather;
    private String previousWeather;
    private boolean timeSeen;
    private long lastTimeNanos;

    /**
     * Records the weather now in effect. Returns true when it differs from the last one seen, then
     * {@link #previousWeather()} holds the one before. The first call after creation or after
     * {@link #forgetWeather()} returns true too, with no previous weather, unless no weather is in effect.
     */
    public synchronized boolean weather(String id) {
        if (!weatherSeen) {
            weatherSeen = true;
            weather = id;
            previousWeather = null;
            return id != null;
        }
        if (Objects.equals(weather, id)) {
            return false;
        }
        previousWeather = weather;
        weather = id;
        return true;
    }

    /** The weather id before the last change, null when there was none. */
    public synchronized String previousWeather() {
        return previousWeather;
    }

    /** The weather id last seen, null when none was seen or none was in effect. */
    public synchronized String currentWeather() {
        return weather;
    }

    /** Drops what was seen, the next read is published again as a first reading. */
    public synchronized void forgetWeather() {
        weatherSeen = false;
        weather = null;
        previousWeather = null;
    }

    /** True when the game time of this world is due at {@code nowNanos}, and marks it as sampled then. */
    public synchronized boolean timeDue(long nowNanos) {
        if (timeSeen && nowNanos - lastTimeNanos < TIME_EVERY_NANOS) {
            return false;
        }
        timeSeen = true;
        lastTimeNanos = nowNanos;
        return true;
    }

    /**
     * Which weather index stands for the whole world. A forced weather wins, as it does in the engine's
     * {@code getEffectiveWeatherIndex}. Otherwise environment 0 when the world has it, else the environment
     * with the lowest id, so the choice is stable from one read to the next. Zero when nothing is known.
     */
    public static int chooseWeatherIndex(int forced, boolean hasEnvironmentZero, int environmentZero,
                                         boolean hasLowest, int lowest) {
        if (forced != 0) {
            return forced;
        }
        if (hasEnvironmentZero) {
            return environmentZero;
        }
        return hasLowest ? lowest : 0;
    }

    /** Days from the game's year zero to {@code date}, so the first in game day is day 0. Minus one when unknown. */
    public static long gameDay(LocalDate date, LocalDate zero) {
        if (date == null || zero == null) {
            return -1;
        }
        return date.toEpochDay() - zero.toEpochDay();
    }
}
