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
import java.util.concurrent.Executor;
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
        counterSink.registerWith(forgetting);
        forgetting.onWorld(counters.worldKeys()::forget);
    }

    /**
     * A hub for tests, built as the plugin builds it but without an engine. Counter file work runs on
     * {@code io}, which may be a direct executor. The IO and timer executors start their threads only when
     * something is queued on them.
     */
    public static Services forTests(HytaleLogger logger, Path dataDir, TrackerConfig config, Executor io) {
        Perf perf = new Perf();
        Publisher publisher = new Publisher(logger, perf, config.queueCapacity, null);
        CounterStore counters = new CounterStore(dataDir, logger, io, perf);
        counters.setPerWorld(config.perWorldCounters);
        CounterSink sink = new CounterSink(counters);
        sink.setCountCreative(config.countCreative);
        publisher.setTracked(config.trackedKinds());
        publisher.addSink(sink);
        ScheduledExecutorService ioThread = io instanceof ScheduledExecutorService s ? s
                : dev.ryvrook.trackerapi.util.Threads.single("TrackerAPI-io", logger);
        return new Services(logger, dataDir, config, perf, publisher, new Dispatcher(publisher, logger, perf), counters, sink,
                new PlayerRegistry(), ioThread, dev.ryvrook.trackerapi.util.Threads.single("TrackerAPI-timer", logger));
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
