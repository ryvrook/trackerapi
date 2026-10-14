package dev.ryvrook.trackerapi.util;

import dev.ryvrook.trackerapi.api.EventKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cost of every engine hook, measured with {@link System#nanoTime()} around each handler body, one row
 * per {@link EventKind} plus rows for the samplers and the dispatch thread. Recording is two
 * {@link LongAdder} adds and one compare on an {@link AtomicLong}, from any thread.
 *
 * <p>The rows for kinds run on engine threads (world threads, the network thread for chat) and are what
 * the verdict judges. The extra rows run on TrackerAPI's own threads and are shown but left out of the
 * verdict.
 */
public final class Perf {
    /** Extra rows after the kinds, in slot order. */
    public static final int R_SAMPLER = EventKind.COUNT, R_DISPATCH = EventKind.COUNT + 1,
            R_JOURNAL = EventKind.COUNT + 2, R_COUNTERS_SAVE = EventKind.COUNT + 3, R_SYNC_LISTENERS = EventKind.COUNT + 4;
    private static final List<String> EXTRA = List.of("sampler", "dispatch", "io.journal", "io.counters.save",
            "sync.listeners");
    private static final int SLOTS = EventKind.COUNT + EXTRA.size();

    /** Total hook cost above which the verdict says watch, half a percent of one thread. */
    public static final double WATCH_MS_PER_MINUTE = 300;
    /** Total hook cost above which the verdict says too costly, 2.5 percent of one thread. */
    public static final double COSTLY_MS_PER_MINUTE = 1_500;
    /** A single call slower than this is worth watching, a world tick at 30 TPS is 33 ms. */
    public static final double WATCH_MAX_MICROS = 20_000;

    private final LongAdder[] calls = new LongAdder[SLOTS];
    private final LongAdder[] totals = new LongAdder[SLOTS];
    private final AtomicLong[] maxima = new AtomicLong[SLOTS];
    private volatile long resetNanos = System.nanoTime();
    private volatile long resetMillis = System.currentTimeMillis();

    public Perf() {
        for (int i = 0; i < SLOTS; i++) {
            calls[i] = new LongAdder();
            totals[i] = new LongAdder();
            maxima[i] = new AtomicLong();
        }
    }

    public void record(EventKind kind, long nanos) {
        record(kind.ordinal(), nanos);
    }

    public void record(int slot, long nanos) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        calls[slot].increment();
        totals[slot].add(nanos);
        AtomicLong max = maxima[slot];
        long seen = max.get();
        while (nanos > seen && !max.compareAndSet(seen, nanos)) {
            seen = max.get();
        }
    }

    public long calls(EventKind kind) {
        return calls[kind.ordinal()].sum();
    }

    public long nanos(EventKind kind) {
        return totals[kind.ordinal()].sum();
    }

    public Map<EventKind, Long> callsByKind() {
        Map<EventKind, Long> out = new EnumMap<>(EventKind.class);
        for (EventKind k : EventKind.values()) {
            out.put(k, calls[k.ordinal()].sum());
        }
        return out;
    }

    public Map<EventKind, Long> nanosByKind() {
        Map<EventKind, Long> out = new EnumMap<>(EventKind.class);
        for (EventKind k : EventKind.values()) {
            out.put(k, totals[k.ordinal()].sum());
        }
        return out;
    }

    public long sinceMillis() {
        return resetMillis;
    }

    public void reset() {
        for (int i = 0; i < SLOTS; i++) {
            calls[i].reset();
            totals[i].reset();
            maxima[i].set(0);
        }
        resetNanos = System.nanoTime();
        resetMillis = System.currentTimeMillis();
    }

    public record Row(String name, long calls, double meanMicros, double maxMicros, double msPerMinute, boolean engine) {
    }

    public record Verdict(String level, String row, String reason) {
        public String line() {
            return "Verdict " + level + ", " + reason;
        }
    }

    /** Rows sorted by milliseconds per minute, highest first. Rows that never ran are left out. */
    public List<Row> rows(long nowNanos) {
        double minutes = Math.max(1e-9, (nowNanos - resetNanos) / (double) TimeUnit.MINUTES.toNanos(1));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            long n = calls[i].sum();
            if (n == 0) {
                continue;
            }
            long total = totals[i].sum();
            String name = i < EventKind.COUNT ? EventKind.values()[i].id() : EXTRA.get(i - EventKind.COUNT);
            boolean engine = i < EventKind.COUNT || i == R_SAMPLER || i == R_SYNC_LISTENERS;
            rows.add(new Row(name, n, total / 1000.0 / n, maxima[i].get() / 1000.0, total / 1e6 / minutes, engine));
        }
        rows.sort(Comparator.comparingDouble(Row::msPerMinute).reversed());
        return rows;
    }

    /** One line judging the engine thread rows. */
    public static Verdict verdict(List<Row> rows) {
        double total = 0;
        Row largest = null;
        Row slowest = null;
        for (Row r : rows) {
            if (!r.engine()) {
                continue;
            }
            total += r.msPerMinute();
            if (largest == null || r.msPerMinute() > largest.msPerMinute()) {
                largest = r;
            }
            if (r.maxMicros() > WATCH_MAX_MICROS && (slowest == null || r.maxMicros() > slowest.maxMicros())) {
                slowest = r;
            }
        }
        if (largest == null) {
            return new Verdict("fine", null, "nothing measured yet");
        }
        String totalText = String.format(java.util.Locale.ROOT, "%.0f ms per minute on engine threads", total);
        if (total >= COSTLY_MS_PER_MINUTE) {
            return new Verdict("too costly", largest.name(), totalText + ", most of it " + largest.name());
        }
        if (total >= WATCH_MS_PER_MINUTE) {
            return new Verdict("watch", largest.name(), totalText + ", most of it " + largest.name());
        }
        if (slowest != null) {
            return new Verdict("watch", slowest.name(), String.format(java.util.Locale.ROOT,
                    "%s had one call of %.1f ms", slowest.name(), slowest.maxMicros() / 1000.0));
        }
        return new Verdict("fine", largest.name(), totalText);
    }
}
