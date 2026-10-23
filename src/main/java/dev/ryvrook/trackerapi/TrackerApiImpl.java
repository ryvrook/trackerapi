package dev.ryvrook.trackerapi;

import dev.ryvrook.trackerapi.api.Counters;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.Stats;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerApi;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** The running API over the services. One per plugin instance. */
final class TrackerApiImpl extends TrackerApi {
    private final Services s;
    private final String version;

    TrackerApiImpl(Services services, String version) {
        this.s = services;
        this.version = version;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends TrackerEvent> Subscription subscribe(String owner, Class<E> type, Delivery delivery, Consumer<? super E> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        EventKind kind = EventKinds.of(type);
        if (kind == null) {
            throw new IllegalArgumentException(type.getName() + " is not a TrackerAPI event type");
        }
        Consumer<TrackerEvent> raw = e -> ((Consumer<E>) listener).accept((E) e);
        return s.publisher.subscribe(owner, Set.of(kind), delivery == null ? Delivery.ASYNC : delivery, raw);
    }

    @Override
    public Subscription subscribeAll(String owner, Delivery delivery, Consumer<TrackerEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        return s.publisher.subscribe(owner, null, delivery == null ? Delivery.ASYNC : delivery, listener);
    }

    @Override
    public Subscription subscribeKinds(String owner, Set<EventKind> kinds, Delivery delivery, Consumer<TrackerEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (kinds == null || kinds.isEmpty()) {
            throw new IllegalArgumentException("kinds must name at least one kind");
        }
        return s.publisher.subscribe(owner, Set.copyOf(kinds), delivery == null ? Delivery.ASYNC : delivery, listener);
    }

    @Override
    public List<Subscription> subscriptions() {
        return s.publisher.subscriptions();
    }

    @Override
    public boolean isTracked(EventKind kind) {
        return kind != null && s.publisher.isTracked(kind);
    }

    @Override
    public PlayerSnapshot snapshot(UUID player) {
        return s.players.snapshot(player);
    }

    @Override
    public List<PlayerSnapshot> snapshots() {
        return s.players.snapshots();
    }

    @Override
    public Counters counters() {
        return s.counters;
    }

    @Override
    public Journal journal() {
        return s.journal();
    }

    @Override
    public Stats stats() {
        Map<EventKind, Long> published = new EnumMap<>(EventKind.class);
        Map<EventKind, Long> dropped = new EnumMap<>(EventKind.class);
        for (EventKind k : EventKind.values()) {
            published.put(k, s.publisher.published(k));
            dropped.put(k, s.publisher.dropped(k));
        }
        var journal = s.journal();
        return new Stats(published, dropped, s.publisher.queued(), s.publisher.queueCapacity(),
                journal == null ? 0 : journal.linesWritten(), journal == null ? 0 : journal.pending(),
                journal == null ? -1 : journal.bytesOnDisk(), s.perf.nanosByKind(), s.perf.callsByKind(), s.perf.sinceMillis());
    }

    @Override
    public List<dev.ryvrook.trackerapi.api.ModInfo> mods() {
        var m = s.mods();
        return m == null ? List.of() : m.infos();
    }

    @Override
    public String version() {
        return version;
    }

    static void publish(TrackerApiImpl api) {
        enable(api);
    }

    static void withdraw(TrackerApiImpl api) {
        disable(api);
    }
}
