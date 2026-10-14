package dev.ryvrook.trackerapi;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.bus.Dispatcher;
import dev.ryvrook.trackerapi.bus.Publisher;
import dev.ryvrook.trackerapi.counters.CounterSink;
import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.facts.Forgetting;
import dev.ryvrook.trackerapi.facts.PlayerRegistry;
import dev.ryvrook.trackerapi.util.Perf;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The service hub every hook and service reads its neighbours from. Built in one pass by the plugin, so
 * nothing here reads another service in its constructor.
 */
public final class Services {
    public final HytaleLogger logger;
    public final Path dataDir;
    public final Perf perf;
    public final Publisher publisher;
    public final Dispatcher dispatcher;
    public final CounterStore counters;
    public final CounterSink counterSink;
    public final PlayerRegistry players;
    public final Forgetting forgetting = new Forgetting();
    public final ScheduledExecutorService io;
    public final ScheduledExecutorService timer;
    private volatile TrackerConfig config;
    private volatile dev.ryvrook.trackerapi.journal.JournalService journal;

    Services(HytaleLogger logger, Path dataDir, TrackerConfig config, Perf perf, Publisher publisher, Dispatcher dispatcher,
             CounterStore counters, CounterSink counterSink, PlayerRegistry players, ScheduledExecutorService io,
             ScheduledExecutorService timer) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.config = config;
        this.perf = perf;
        this.publisher = publisher;
        this.dispatcher = dispatcher;
        this.counters = counters;
        this.counterSink = counterSink;
        this.players = players;
        this.io = io;
        this.timer = timer;
    }

    public TrackerConfig config() {
        return config;
    }

    void setConfig(TrackerConfig config) {
        this.config = config;
    }

    public dev.ryvrook.trackerapi.journal.JournalService journal() {
        return journal;
    }

    void setJournal(dev.ryvrook.trackerapi.journal.JournalService journal) {
        this.journal = journal;
    }

    /** A header for an event observed now. Call it only after {@code publisher.wants(kind)} said yes. */
    public Header header(String world, UUID player) {
        return new Header(System.currentTimeMillis(), publisher.nextSeq(), world, player);
    }
}
