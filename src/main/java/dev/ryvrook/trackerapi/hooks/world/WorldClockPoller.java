package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.GameTimeSampled;
import dev.ryvrook.trackerapi.api.event.WeatherChanged;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIterator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * WEATHER_CHANGED and GAME_TIME_SAMPLED. The engine has no event for either, so once a second the timer posts
 * one task per world that has players, through a {@link WorldTasks.Gate} so a lagging world skips rounds
 * instead of queueing them. The task reads {@code WeatherResource} and {@code WorldTimeResource} on that
 * world's thread and diffs against the world's {@link WorldClock}. Weather is published on change, the game
 * time once a minute per world. This is separate from the player sampler and keeps its own per world maps,
 * which a world removal drops through {@code Forgetting}.
 *
 * <p>Threads. {@link #poll} runs on {@code TrackerAPI-timer} and only posts, the reads run on world threads.
 * A {@code LinkageError} in one read turns that read off with one warning, the other carries on.
 */
public final class WorldClockPoller {
    private static final long PERIOD_SECONDS = 1;
    private static final long STALE_MILLIS = 30_000;

    private final Services s;
    private final WorldTasks.Gate gate = new WorldTasks.Gate(STALE_MILLIS);
    private final ConcurrentHashMap<String, WorldClock> clocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, World> worlds = new ConcurrentHashMap<>();
    private volatile boolean weatherOn;
    private volatile boolean timeOn;
    private volatile LocalDate zeroDay;
    private ScheduledFuture<?> future;

    public WorldClockPoller(Services services) {
        this.s = services;
        services.forgetting.onWorld(this::forget);
    }

    /** Setup, in a registrar guard. Touches the weather classes so a reshaped engine fails here, not later. */
    public void enableWeather() {
        WeatherResource.getResourceType();
        Weather.getAssetMap();
        weatherOn = true;
    }

    /** Setup, in a registrar guard. Touches the time class and reads the game's year zero. */
    public void enableTime() {
        WorldTimeResource.getResourceType();
        zeroDay = LocalDateTime.ofInstant(WorldTimeResource.ZERO_YEAR, WorldTimeResource.ZONE_OFFSET).toLocalDate();
        timeOn = true;
    }

    public synchronized void start() {
        if ((weatherOn || timeOn) && future == null) {
            future = s.timer.scheduleAtFixedRate(this::poll, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        clocks.clear();
        worlds.clear();
    }

    /** Drops what is kept for a removed world. Any thread. */
    void forget(String name) {
        if (name == null) {
            return;
        }
        clocks.remove(name);
        World world = worlds.remove(name);
        gate.forget(world);
    }

    private void poll() {
        boolean weather = weatherOn && s.publisher.wants(EventKind.WEATHER_CHANGED);
        boolean time = timeOn && s.publisher.wants(EventKind.GAME_TIME_SAMPLED);
        if (!weather) {
            // a later subscriber gets a fresh baseline, not a change against a stale read
            for (WorldClock clock : clocks.values()) {
                clock.forgetWeather();
            }
        }
        if (!weather && !time) {
            return;
        }
        Universe universe;
        Map<String, World> all;
        try {
            universe = Universe.get();
            all = universe == null ? null : universe.getWorlds();
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
            return;
        }
        if (all == null) {
            return;
        }
        for (World world : all.values()) {
            try {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                String name = world.getName();
                if (name == null) {
                    continue;
                }
                worlds.put(name, world);
                WorldClock clock = clocks.computeIfAbsent(name, k -> new WorldClock());
                gate.post(world, () -> read(world, name, clock, weather, time));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            }
        }
        // a removal the listener missed still drops its world here
        for (String name : clocks.keySet()) {
            if (!all.containsKey(name)) {
                forget(name);
            }
        }
    }

    private void read(World world, String name, WorldClock clock, boolean weather, boolean time) {
        long start = System.nanoTime();
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (weather && weatherOn) {
                readWeather(store, name, clock);
            }
            if (time && timeOn && clock.timeDue(start)) {
                readTime(store, name);
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.WEATHER_CHANGED, System.nanoTime() - start);
        }
    }

    private void readWeather(Store<EntityStore> store, String name, WorldClock clock) {
        try {
            WeatherResource res = store.getResource(WeatherResource.getResourceType());
            if (res == null) {
                return;
            }
            String id = weatherId(weatherIndex(res));
            if (clock.weather(id) && s.publisher.wants(EventKind.WEATHER_CHANGED)) {
                s.publisher.publish(new WeatherChanged(s.header(name, null), id, clock.previousWeather()));
            }
        } catch (LinkageError e) {
            weatherOn = false;
            s.logger.at(Level.WARNING).withCause(e).log("TrackerAPI weather tracking is not supported on this server and is off");
        } catch (RuntimeException e) {
            Guard.failed(e);
        }
    }

    private void readTime(Store<EntityStore> store, String name) {
        try {
            WorldTimeResource t = store.getResource(WorldTimeResource.getResourceType());
            if (t == null || !s.publisher.wants(EventKind.GAME_TIME_SAMPLED)) {
                return;
            }
            LocalDateTime date = t.getGameDateTime();
            long day = WorldClock.gameDay(date == null ? null : date.toLocalDate(), zeroDay);
            s.publisher.publish(new GameTimeSampled(s.header(name, null), t.getCurrentHour(), t.getDayProgress(),
                    t.getMoonPhase(), day));
        } catch (LinkageError e) {
            timeOn = false;
            s.logger.at(Level.WARNING).withCause(e).log("TrackerAPI game time tracking is not supported on this server and is off");
        } catch (RuntimeException e) {
            Guard.failed(e);
        }
    }

    /** The weather index that stands for the world, see {@link WorldClock#chooseWeatherIndex}. */
    private static int weatherIndex(WeatherResource res) {
        int forced = res.getForcedWeatherIndex();
        if (forced != 0) {
            return forced;
        }
        Int2IntMap env = res.getEnvironmentWeather();
        if (env == null || env.isEmpty()) {
            return 0;
        }
        boolean hasZero = env.containsKey(0);
        boolean hasLowest = false;
        int lowestKey = 0;
        if (!hasZero) {
            IntIterator it = env.keySet().iterator();
            while (it.hasNext()) {
                int key = it.nextInt();
                if (!hasLowest || key < lowestKey) {
                    lowestKey = key;
                    hasLowest = true;
                }
            }
        }
        return WorldClock.chooseWeatherIndex(0, hasZero, hasZero ? env.get(0) : 0, hasLowest,
                hasLowest ? env.get(lowestKey) : 0);
    }

    /** The asset id of a weather index, null when no weather is in effect. */
    private static String weatherId(int index) {
        if (index <= 0) {
            return null;
        }
        Weather w = Weather.getAssetMap().getAsset(index);
        return w == null || w.getId() == null ? "weather_" + index : w.getId();
    }
}
