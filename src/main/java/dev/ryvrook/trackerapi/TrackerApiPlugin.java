package dev.ryvrook.trackerapi;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.ryvrook.trackerapi.api.event.ServerLifecycle;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.bus.Dispatcher;
import dev.ryvrook.trackerapi.bus.Publisher;
import dev.ryvrook.trackerapi.counters.CounterSink;
import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.facts.PlayerRegistry;
import dev.ryvrook.trackerapi.hooks.HookSet;
import dev.ryvrook.trackerapi.hooks.Hooks;
import dev.ryvrook.trackerapi.hooks.Registrar;
import dev.ryvrook.trackerapi.util.Perf;
import dev.ryvrook.trackerapi.util.Threads;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * TrackerAPI records every interaction on the server once and hands it to other plugins.
 *
 * <p>Lifecycle. {@code setup()} reads the config, builds the services, installs the hooks and the sinks.
 * {@code start()} starts the dispatch thread, the timers, the samplers, publishes the API and runs the
 * import when it is the first boot. {@code shutdown()} withdraws the API first so consumers stop
 * subscribing, stops the hooks, drains the dispatcher, saves everything and stops the threads. Nothing
 * of the old instance survives a {@code /plugin reload}.
 */
public final class TrackerApiPlugin extends JavaPlugin {
    private static final long DRAIN_SECONDS = 10;

    private Services services;
    private TrackerApiImpl api;
    private Registrar registrar;
    private List<HookSet> hooks = List.of();
    private ScheduledFuture<?> autosave;
    private ScheduledFuture<?> boards;
    private dev.ryvrook.trackerapi.command.TrackerCommand command;
    private volatile boolean stopping;

    public TrackerApiPlugin(JavaPluginInit init) {
        super(init);
    }

    public Services services() {
        return services;
    }

    public String version() {
        try {
            return getManifest().getVersion().toString();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    public Path configFile() {
        return getDataDirectory().resolve("TrackerAPI.json");
    }

    @Override
    protected void setup() {
        Path data = getDataDirectory();
        TrackerConfig config = TrackerConfig.load(configFile(), getLogger());
        Perf perf = new Perf();
        ScheduledExecutorService io = Threads.single("TrackerAPI-io", getLogger());
        ScheduledExecutorService timer = Threads.single("TrackerAPI-timer", getLogger());
        Dispatcher[] dispatcherRef = new Dispatcher[1];
        Publisher publisher = new Publisher(getLogger(), perf, config.queueCapacity, () -> {
            Dispatcher d = dispatcherRef[0];
            if (d != null) {
                d.wake();
            }
        });
        Dispatcher dispatcher = new Dispatcher(publisher, getLogger(), perf);
        dispatcherRef[0] = dispatcher;
        dispatcher.setBudgetMillis(config.listenerBudgetMillis);
        CounterStore counters = new CounterStore(data, getLogger(), io, perf);
        counters.setPerWorld(config.perWorldCounters);
        CounterSink counterSink = new CounterSink(counters);
        counterSink.setCountCreative(config.countCreative);
        PlayerRegistry players = new PlayerRegistry();
        services = new Services(getLogger(), data, config, perf, publisher, dispatcher, counters, counterSink, players, io, timer);
        publisher.setTracked(config.trackedKinds());
        publisher.addSink(counterSink);
        if (config.journalEnabled) {
            try {
                var journal = dev.ryvrook.trackerapi.journal.FileJournal.create(services, data.resolve("journal"),
                        config.journalKinds, config.journalRetentionDays, config.journalMaxMegabytes);
                services.setJournal(journal);
                publisher.addSink(journal);
            } catch (RuntimeException | LinkageError e) {
                getLogger().at(Level.WARNING).withCause(e).log("TrackerAPI could not open its journal, it is off for this run");
            }
        }
        loadServerFiles(counters);

        registrar = new Registrar(this, getLogger());
        hooks = Hooks.all(services);
        for (HookSet set : hooks) {
            try {
                set.install(this, services, registrar);
            } catch (RuntimeException | LinkageError e) {
                getLogger().at(Level.WARNING).withCause(e).log("TrackerAPI could not install its %s hooks", set.name());
            }
        }
        try {
            command = new dev.ryvrook.trackerapi.command.TrackerCommand(this);
            getCommandRegistry().registerCommand(command);
        } catch (RuntimeException | LinkageError e) {
            getLogger().at(Level.WARNING).withCause(e).log("TrackerAPI could not register its command");
        }
        getLogger().at(Level.INFO).log("TrackerAPI %s set up, %d hooks registered, %d unsupported, %d kinds tracked, journal %s",
                version(), registrar.registered().size(), registrar.failed().size(), config.trackedKinds().size(),
                services.journal() == null ? "off" : "on");
    }

    private void loadServerFiles(CounterStore counters) {
        try {
            services.io.submit(counters::loadServer).get(DRAIN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            getLogger().at(Level.WARNING).withCause(e).log("TrackerAPI could not read its server files");
        }
    }

    @Override
    protected void start() {
        TrackerConfig config = services.config();
        services.dispatcher.start();
        for (HookSet set : hooks) {
            try {
                set.start();
            } catch (RuntimeException | LinkageError e) {
                getLogger().at(Level.WARNING).withCause(e).log("TrackerAPI could not start its %s hooks", set.name());
            }
        }
        if (services.journal() != null) {
            services.journal().start();
        }
        autosave = services.timer.scheduleAtFixedRate(services.counters::autosave, config.autosaveSeconds,
                config.autosaveSeconds, TimeUnit.SECONDS);
        if (config.boardSeconds > 0) {
            boards = services.timer.scheduleAtFixedRate(() -> services.io.execute(services.counters.boards()::rebuild),
                    5, config.boardSeconds, TimeUnit.SECONDS);
        }
        api = new TrackerApiImpl(services, version());
        TrackerApiImpl.publish(api);
        if (config.importOnFirstBoot) {
            services.io.execute(() -> new dev.ryvrook.trackerapi.imports.Importer(services).runFirstBootIfNeeded());
        }
        if (services.publisher.wants(EventKind.SERVER_LIFECYCLE)) {
            services.publisher.publish(new ServerLifecycle(services.header(null, null), ServerLifecycle.Phase.STARTED));
        }
        getLogger().at(Level.INFO).log("TrackerAPI %s started", version());
        reloadDependentsAfterReload();
    }

    /**
     * After a reload of TrackerAPI the plugins that use it are still linked to the old instance. They are
     * reloaded here, a moment after this start returned, so the owner does not have to remember the order.
     * On a fresh boot no dependent is enabled yet and nothing happens.
     */
    private void reloadDependentsAfterReload() {
        java.util.List<com.hypixel.hytale.common.plugin.PluginIdentifier> dependents = Dependents.enabled(getIdentifier());
        if (dependents.isEmpty()) {
            return;
        }
        getLogger().at(Level.INFO).log("TrackerAPI found %d plugins linked to its previous instance and reloads them in two seconds", dependents.size());
        services.timer.schedule(() -> {
            if (stopping) {
                return;
            }
            Dependents.reload(dependents, getLogger());
        }, 2, TimeUnit.SECONDS);
    }

    @Override
    protected void shutdown() {
        stopping = true;
        if (api != null) {
            if (services.publisher.wants(EventKind.SERVER_LIFECYCLE)) {
                services.publisher.publish(new ServerLifecycle(services.header(null, null), ServerLifecycle.Phase.STOPPING));
            }
            TrackerApiImpl.withdraw(api);
        }
        for (HookSet set : hooks) {
            try {
                set.stop();
            } catch (RuntimeException | LinkageError e) {
                getLogger().at(Level.FINE).withCause(e).log("TrackerAPI %s hooks failed to stop", set.name());
            }
        }
        if (autosave != null) {
            autosave.cancel(false);
        }
        if (boards != null) {
            boards.cancel(false);
        }
        // Stop taking events first, so nothing is published while the queue drains, then drop the listeners.
        services.publisher.stopAccepting();
        services.dispatcher.stop(TimeUnit.SECONDS.toMillis(DRAIN_SECONDS));
        services.publisher.close();
        if (services.journal() != null) {
            services.journal().stop();
        }
        services.counters.stop();
        long now = System.currentTimeMillis();
        for (var online : services.players.all()) {
            // The session keeps running across a reload, so its start stays in the file and lastSeen marks
            // when the file was written, see PlayerCounters.mergeFromDisk.
            services.counters.raiseSession(online.uuid, online.sessionStart, now);
            var live = services.counters.live(online.uuid);
            if (live != null) {
                live.touch(now);
            }
            services.counters.leave(online.uuid);
        }
        try {
            services.io.execute(services.counters::saveAll); // the final save, queued on IO before it drains
        } catch (java.util.concurrent.RejectedExecutionException e) {
            services.counters.saveAll();
        }
        Threads.drain(services.timer, "TrackerAPI timer", 2, getLogger());
        Threads.drain(services.io, "TrackerAPI io", DRAIN_SECONDS, getLogger());
        int stragglers = registrar == null ? 0 : registrar.unregisterStragglers();
        if (stragglers > 0) {
            getLogger().at(Level.INFO).log("TrackerAPI removed %d straggling systems", stragglers);
        }
        services.players.clear();
    }

    public boolean isStopping() {
        return stopping;
    }

    /** {@code /trackerapi reload}. Re-reads the config on IO and applies what can change live. */
    public java.util.concurrent.CompletableFuture<String> reload() {
        var result = new java.util.concurrent.CompletableFuture<String>();
        if (stopping) {
            getLogger().at(Level.FINE).log("TrackerAPI skipped a config reload while stopping");
            result.complete("TrackerAPI is stopping, the config was not reloaded.");
            return result;
        }
        try {
            services.io.execute(() -> reloadOnIo(result));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            getLogger().at(Level.FINE).log("TrackerAPI skipped a config reload, its IO thread is gone");
            result.complete("TrackerAPI is stopping, the config was not reloaded.");
        }
        return result;
    }

    private void reloadOnIo(java.util.concurrent.CompletableFuture<String> result) {
        TrackerConfig next = TrackerConfig.reload(configFile(), getLogger());
        if (next == null) {
            result.complete("TrackerAPI.json could not be read, the running settings stay. See the server log.");
            return;
        }
        services.setConfig(next);
        services.publisher.setTracked(next.trackedKinds());
        services.dispatcher.setBudgetMillis(next.listenerBudgetMillis);
        services.counters.setPerWorld(next.perWorldCounters);
        services.counterSink.setCountCreative(next.countCreative);
        if (services.journal() != null) {
            services.journal().reconfigure(next.journalKinds, next.journalRetentionDays, next.journalMaxMegabytes);
        }
        services.publisher.rebuild();
        result.complete("TrackerAPI.json reloaded. " + next.trackedKinds().size() + " kinds tracked. Queue size, "
                + "sample interval and journal on or off apply at the next restart.");
    }
}
