package dev.ryvrook.trackerapi.journal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.util.Guard;
import dev.ryvrook.trackerapi.util.Perf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The journal on disk. One folder per world under {@code journal/}, {@code _server} for events of no world,
 * one file per hour named {@code yyyy-MM-dd-HH.jsonl} in UTC, one JSON line per event. The file of the
 * current hour stays open, is flushed every second and whenever 4,096 lines are waiting, and is closed and
 * gzipped to {@code .jsonl.gz} once the hour has passed.
 *
 * <p>{@link #accept} runs on the publishing thread and only enqueues. Everything that touches a file, the
 * writes, the gzip, the prune and the queries, runs on the IO executor. Queries scan one hour at a time and
 * hand the executor back between hours, so writes keep flowing while a long query runs. At most four
 * queries scan at once, the rest wait their turn.
 */
public final class FileJournal implements JournalService {
    static final int QUEUE_CAPACITY = 65_536;
    static final int FLUSH_LINES = 4_096;
    static final int MAX_QUERIES = 4;
    static final int MAX_LIMIT = 10_000;
    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    static final long PRUNE_SECONDS = TimeUnit.MINUTES.toSeconds(10);
    static final String SERVER_FOLDER = "_server";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    private static final Gson LINE = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Map<String, EventKind> BY_ID = new HashMap<>();

    static {
        for (EventKind k : EventKind.values()) {
            BY_ID.put(k.id(), k);
        }
    }

    private final HytaleLogger logger;
    private final Path folder;
    private final Executor io;
    private final ScheduledExecutorService scheduler;
    private final Perf perf;
    private final LongSupplier clock;
    private final Guard.RateLimiter warnings = new Guard.RateLimiter(TimeUnit.MINUTES.toNanos(1));

    private volatile boolean[] wanted = new boolean[EventKind.COUNT];
    private volatile Set<EventKind> kinds;
    private volatile int retentionDays;
    private volatile int maxMegabytes;
    private volatile boolean stopped;

    private final ConcurrentLinkedQueue<TrackerEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicBoolean drainAsked = new AtomicBoolean();
    private final LongAdder dropped = new LongAdder();
    private volatile long lines;
    private volatile long bytes;
    private volatile long corrupt;

    // IO thread only
    private final Map<String, Open> open = new HashMap<>();
    private final ArrayDeque<QueryJob> waiting = new ArrayDeque<>();
    private int running;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();

    /** The current hour's file of one world folder. */
    private static final class Open {
        final String dir;
        final long hour;
        final Path file;
        final BufferedWriter writer;
        int unflushed;

        Open(String dir, long hour, Path file, BufferedWriter writer) {
            this.dir = dir;
            this.hour = hour;
            this.file = file;
            this.writer = writer;
        }
    }

    FileJournal(HytaleLogger logger, Path folder, Executor io, ScheduledExecutorService scheduler, Perf perf,
                LongSupplier clock, Set<EventKind> kinds, int retentionDays, int maxMegabytes) {
        this.logger = logger;
        this.folder = folder;
        this.io = io;
        this.scheduler = scheduler;
        this.perf = perf;
        this.clock = clock;
        reconfigure(kinds, retentionDays, maxMegabytes);
    }

    /** The journal of a running plugin, writing through {@code services.io}. */
    public static JournalService create(Services services, Path folder, Set<EventKind> kinds, int retentionDays, int maxMegabytes) {
        return new FileJournal(services.logger, folder, services.io, services.io, services.perf, System::currentTimeMillis,
                kinds, retentionDays, maxMegabytes);
    }

    // ---- sink, any thread --------------------------------------------------------------------------------

    @Override
    public boolean wants(EventKind kind) {
        return !stopped && wanted[kind.ordinal()];
    }

    @Override
    public void accept(TrackerEvent event) {
        if (stopped || event == null) {
            return;
        }
        if (size.incrementAndGet() > QUEUE_CAPACITY) {
            size.decrementAndGet();
            dropped.increment();
            if (warnings.allow("full")) {
                logger.at(Level.WARNING).log("TrackerAPI journal queue is full (%d lines), dropping events until the IO thread catches up",
                        QUEUE_CAPACITY);
            }
            return;
        }
        queue.add(event);
        if (size.get() >= FLUSH_LINES && drainAsked.compareAndSet(false, true)) {
            run(this::tick);
        }
    }

    @Override
    public long linesWritten() {
        return lines;
    }

    @Override
    public int pending() {
        return size.get();
    }

    @Override
    public long dropped() {
        return dropped.sum();
    }

    /** Lines skipped by queries because they did not parse, since start. */
    public long corruptLines() {
        return corrupt;
    }

    @Override
    public Set<EventKind> kinds() {
        return kinds;
    }

    @Override
    public Path folder() {
        return folder;
    }

    @Override
    public long bytesOnDisk() {
        return bytes;
    }

    @Override
    public void reconfigure(Set<EventKind> next, int retentionDays, int maxMegabytes) {
        boolean[] w = new boolean[EventKind.COUNT];
        EnumSet<EventKind> copy = EnumSet.noneOf(EventKind.class);
        if (next != null) {
            copy.addAll(next);
        }
        for (EventKind k : copy) {
            w[k.ordinal()] = true;
        }
        this.kinds = Collections.unmodifiableSet(copy);
        this.retentionDays = Math.max(1, retentionDays);
        this.maxMegabytes = Math.max(1, maxMegabytes);
        this.wanted = w;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    @Override
    public void start() {
        run(this::recover);
        if (scheduler != null) {
            timers.add(scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS));
            timers.add(scheduler.scheduleWithFixedDelay(this::prune, 30, PRUNE_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Override
    public void stop() {
        stopped = true;
        for (ScheduledFuture<?> f : timers) {
            f.cancel(false);
        }
        timers.clear();
        run(() -> {
            tick();
            for (Open o : new ArrayList<>(open.values())) {
                closeQuietly(o);
            }
            open.clear();
            QueryJob job;
            while ((job = waiting.poll()) != null) {
                job.future.complete(List.of());
            }
        });
    }

    private void run(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            // the IO thread is gone, the plugin is stopping
        }
    }

    // ---- writing, IO thread ------------------------------------------------------------------------------

    /** IO thread. Writes what is queued, flushes, and closes and gzips the files of hours that have passed. */
    void tick() {
        drainAsked.set(false);
        long start = System.nanoTime();
        try {
            TrackerEvent e;
            while ((e = queue.poll()) != null) {
                size.decrementAndGet();
                write(e);
            }
            for (Open o : new ArrayList<>(open.values())) {
                if (o.unflushed > 0) {
                    flush(o);
                }
            }
            long hour = clock.getAsLong() / HOUR;
            for (Open o : new ArrayList<>(open.values())) {
                if (o.hour < hour) {
                    roll(o);
                }
            }
        } catch (RuntimeException ex) {
            Guard.failed(ex, "TrackerAPI journal write failed");
        } finally {
            if (perf != null) {
                perf.record(Perf.R_JOURNAL, System.nanoTime() - start);
            }
        }
    }

    private void write(TrackerEvent event) {
        String dir = folderName(event.world());
        long hour = Math.floorDiv(event.at(), HOUR);
        Open o = open.get(dir);
        if (o != null && hour > o.hour) {
            roll(o);
            o = null;
        }
        String line;
        try {
            line = LINE.toJson(EventJson.toJson(event));
        } catch (RuntimeException ex) {
            Guard.failed(ex, "TrackerAPI could not write a " + event.kind().id() + " event to its journal");
            return;
        }
        try {
            if (o == null) {
                o = openFile(dir, hour);
            }
            o.writer.write(line);
            o.writer.write('\n');
            o.unflushed++;
            lines++;
            bytes += line.length() + 1;
            if (o.unflushed >= FLUSH_LINES) {
                flush(o);
            }
        } catch (IOException ex) {
            if (warnings.allow("write")) {
                logger.at(Level.WARNING).withCause(ex).log("TrackerAPI could not write to its journal in %s", dir);
            }
            if (o != null) {
                closeQuietly(o);
                open.remove(dir);
            }
        }
    }

    private Open openFile(String dir, long hour) throws IOException {
        Path d = folder.resolve(dir);
        Files.createDirectories(d);
        Path file = d.resolve(fileName(hour) + ".jsonl");
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        Open o = new Open(dir, hour, file, w);
        open.put(dir, o);
        return o;
    }

    private void flush(Open o) {
        try {
            o.writer.flush();
            o.unflushed = 0;
        } catch (IOException ex) {
            if (warnings.allow("flush")) {
                logger.at(Level.WARNING).withCause(ex).log("TrackerAPI could not flush its journal in %s", o.dir);
            }
        }
    }

    /** Closes the file of a passed hour and gzips it. */
    private void roll(Open o) {
        closeQuietly(o);
        open.remove(o.dir, o);
        gzip(o.file);
    }

    private void closeQuietly(Open o) {
        try {
            o.writer.close();
        } catch (IOException ignored) {
            // the next open starts a fresh writer
        }
    }

    /**
     * Compresses a closed hour to {@code .jsonl.gz} through a temp file. When a gzip of the same hour exists
     * already the new lines become a second gzip member of it, which readers see as one stream.
     */
    void gzip(Path plain) {
        if (!Files.exists(plain)) {
            return;
        }
        Path target = plain.resolveSibling(plain.getFileName() + ".gz");
        Path temp = plain.resolveSibling(plain.getFileName() + ".gz.tmp");
        try {
            long before = Files.size(plain) + (Files.exists(target) ? Files.size(target) : 0);
            if (Files.exists(target)) {
                Files.copy(target, temp, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(temp);
            }
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE), 65_536)) {
                Files.copy(plain, out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(plain);
            bytes = Math.max(0, bytes - before + Files.size(target));
        } catch (IOException ex) {
            logger.at(Level.WARNING).withCause(ex).log("TrackerAPI could not compress %s, it stays as it is", plain.getFileName());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // left for the next start
            }
        }
    }

    /** IO thread, at start. Gzips the plain files a crash or a stop left behind for hours that have passed. */
    void recover() {
        long hour = clock.getAsLong() / HOUR;
        for (FileInfo f : files(null)) {
            if (!f.gz && f.hour < hour && !isOpen(f.path)) {
                gzip(f.path);
            }
        }
        bytes = total(files(null));
    }

    // ---- retention, IO thread ----------------------------------------------------------------------------

    /** IO thread. Deletes hours older than the retention and then the oldest hours until under the size cap. */
    void prune() {
        try {
            long now = clock.getAsLong();
            long cutoff = now - TimeUnit.DAYS.toMillis(retentionDays);
            List<FileInfo> all = files(null);
            List<FileInfo> kept = new ArrayList<>(all.size());
            for (FileInfo f : all) {
                if ((f.hour + 1) * HOUR <= cutoff && !isOpen(f.path)) {
                    delete(f);
                } else {
                    kept.add(f);
                }
            }
            long cap = maxMegabytes * 1024L * 1024L;
            long total = total(kept);
            if (total > cap) {
                kept.sort(Comparator.comparingLong((FileInfo f) -> f.hour).thenComparing(f -> f.path.toString()));
                Iterator<FileInfo> it = kept.iterator();
                while (total > cap && it.hasNext()) {
                    FileInfo f = it.next();
                    if (isOpen(f.path)) {
                        continue;
                    }
                    if (delete(f)) {
                        total -= f.size;
                    }
                }
            }
            bytes = total;
        } catch (RuntimeException ex) {
            Guard.failed(ex, "TrackerAPI journal prune failed");
        }
    }

    private boolean delete(FileInfo f) {
        try {
            Files.deleteIfExists(f.path);
            return true;
        } catch (IOException ex) {
            if (warnings.allow("delete")) {
                logger.at(Level.WARNING).withCause(ex).log("TrackerAPI could not delete the old journal file %s", f.path);
            }
            return false;
        }
    }

    private boolean isOpen(Path p) {
        for (Open o : open.values()) {
            if (o.file.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private static long total(List<FileInfo> files) {
        long sum = 0;
        for (FileInfo f : files) {
            sum += f.size;
        }
        return sum;
    }

    record FileInfo(Path path, String dir, long hour, boolean gz, long size) {
    }

    /** Every journal file, of one world folder when {@code onlyDir} is set. */
    List<FileInfo> files(String onlyDir) {
        List<FileInfo> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        List<Path> dirs = new ArrayList<>();
        if (onlyDir != null) {
            dirs.add(folder.resolve(onlyDir));
        } else {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) {
                        dirs.add(p);
                    }
                }
            } catch (IOException ex) {
                logger.at(Level.WARNING).withCause(ex).log("TrackerAPI could not list its journal folder");
            }
        }
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    boolean gz = name.endsWith(".jsonl.gz");
                    if (!gz && !name.endsWith(".jsonl")) {
                        continue;
                    }
                    long hour = hourOf(name);
                    if (hour < 0) {
                        continue;
                    }
                    long size;
                    try {
                        size = Files.size(p);
                    } catch (IOException ex) {
                        size = 0;
                    }
                    out.add(new FileInfo(p, d.getFileName().toString(), hour, gz, size));
                }
            } catch (IOException ex) {
                logger.at(Level.WARNING).withCause(ex).log("TrackerAPI could not list the journal folder %s", d.getFileName());
            }
        }
        return out;
    }

    // ---- names -------------------------------------------------------------------------------------------

    /** The folder of a world, {@code _server} for none. Characters a file system may refuse become underscores. */
    static String folderName(String world) {
        if (world == null || world.isEmpty()) {
            return SERVER_FOLDER;
        }
        StringBuilder sb = new StringBuilder(world.length());
        for (int i = 0; i < world.length(); i++) {
            char c = world.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' ? c : '_');
        }
        String s = sb.toString();
        return s.equals(".") || s.equals("..") || s.equals(SERVER_FOLDER) ? "_" + s : s;
    }

    /** {@code yyyy-MM-dd-HH} of an hour since the epoch, in UTC. */
    static String fileName(long hour) {
        return LocalDateTime.ofEpochSecond(hour * 3600L, 0, ZoneOffset.UTC).format(NAME);
    }

    /** The hour since the epoch a file name stands for, or -1. */
    static long hourOf(String name) {
        if (name.length() < 13) {
            return -1;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(name.substring(0, 13) + ":00", DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm"));
            return t.toEpochSecond(ZoneOffset.UTC) / 3600L;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---- queries -----------------------------------------------------------------------------------------

    @Override
    public CompletableFuture<List<Entry>> find(Query query) {
        CompletableFuture<List<Entry>> future = new CompletableFuture<>();
        if (query == null || stopped) {
            future.complete(List.of());
            return future;
        }
        QueryJob job = new QueryJob(query, future);
        try {
            io.execute(() -> {
                waiting.add(job);
                pump();
            });
        } catch (RejectedExecutionException e) {
            future.complete(List.of());
        }
        return future;
    }

    /** IO thread. Starts waiting queries while fewer than four run. */
    private void pump() {
        while (running < MAX_QUERIES && !waiting.isEmpty()) {
            QueryJob job = waiting.poll();
            running++;
            try {
                job.plan();
            } catch (RuntimeException ex) {
                job.finish(ex);
                continue;
            }
            run(job::step);
        }
    }

    /** One query, scanning one hour per IO task, newest first. */
    private final class QueryJob {
        final Query q;
        final CompletableFuture<List<Entry>> future;
        final int limit;
        final String playerText;
        final List<Entry> found = new ArrayList<>();
        Iterator<Map.Entry<Long, List<FileInfo>>> hours;
        boolean done;

        QueryJob(Query q, CompletableFuture<List<Entry>> future) {
            this.q = q;
            this.future = future;
            this.limit = Math.max(1, Math.min(MAX_LIMIT, q.limit()));
            this.playerText = q.player() == null ? null : q.player().toString();
        }

        void plan() {
            String dir = q.world() == null ? null : folderName(q.world());
            TreeMap<Long, List<FileInfo>> byHour = new TreeMap<>(Comparator.reverseOrder());
            long from = q.fromMillis();
            long to = q.toMillis();
            for (FileInfo f : files(dir)) {
                long startMs = f.hour * HOUR;
                if (startMs <= to && startMs + HOUR > from) {
                    byHour.computeIfAbsent(f.hour, h -> new ArrayList<>()).add(f);
                }
            }
            hours = byHour.entrySet().iterator();
        }

        void step() {
            if (done) {
                return;
            }
            try {
                if (stopped || found.size() >= limit || !hours.hasNext()) {
                    finish(null);
                    return;
                }
                Map.Entry<Long, List<FileInfo>> next = hours.next();
                for (Open o : open.values()) {
                    if (o.hour == next.getKey() && o.unflushed > 0) {
                        flush(o);
                    }
                }
                List<Entry> hour = new ArrayList<>();
                for (FileInfo f : next.getValue()) {
                    scan(f, hour);
                }
                hour.sort(Comparator.comparingLong(Entry::at).thenComparingLong(Entry::seq).reversed());
                for (Entry e : hour) {
                    if (found.size() >= limit) {
                        break;
                    }
                    found.add(e);
                }
            } catch (RuntimeException ex) {
                finish(ex);
                return;
            }
            run(this::step);
        }

        void finish(Throwable failure) {
            if (done) {
                return;
            }
            done = true;
            running--;
            if (failure != null) {
                future.completeExceptionally(failure);
            } else {
                future.complete(List.copyOf(found));
            }
            pump();
        }

        private void scan(FileInfo f, List<Entry> out) {
            try (InputStream raw = Files.newInputStream(f.path);
                 InputStream in = f.gz ? new GZIPInputStream(raw, 65_536) : raw;
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65_536)) {
                String line;
                while ((line = r.readLine()) != null) {
                    Entry e = match(line);
                    if (e != null) {
                        out.add(e);
                    }
                }
            } catch (IOException ex) {
                // a gzip cut short by a crash keeps the lines read so far
                if (warnings.allow("read:" + f.path)) {
                    logger.at(Level.FINE).withCause(ex).log("TrackerAPI could not read all of %s", f.path);
                }
            }
        }

        Entry match(String line) {
            if (line.isEmpty()) {
                return null;
            }
            Head h = Head.parse(line);
            if (h == null) {
                h = Head.slow(line);
                if (h == null) {
                    corrupt++;
                    return null;
                }
            }
            EventKind kind = BY_ID.get(h.kind);
            if (kind == null) {
                return null;
            }
            Set<EventKind> ks = q.kinds();
            if (ks != null && !ks.isEmpty() && !ks.contains(kind)) {
                return null;
            }
            if (playerText != null && !playerText.equalsIgnoreCase(h.player)) {
                return null;
            }
            if (q.world() != null && !q.world().equals(h.world)) {
                return null;
            }
            if (h.at < q.fromMillis() || h.at > q.toMillis()) {
                return null;
            }
            JsonObject full;
            try {
                JsonElement e = JsonParser.parseString(line);
                if (!e.isJsonObject()) {
                    corrupt++;
                    return null;
                }
                full = e.getAsJsonObject();
            } catch (RuntimeException ex) {
                corrupt++;
                return null;
            }
            if (q.hasBox() && !inBox(full, q)) {
                return null;
            }
            UUID player = null;
            if (h.player != null) {
                try {
                    player = UUID.fromString(h.player);
                } catch (IllegalArgumentException ignored) {
                    // left null
                }
            }
            return new Entry(kind, h.at, h.seq, h.world, player, line);
        }
    }

    /** True when the record has a position and it lies in the query's box. */
    static boolean inBox(JsonObject o, Query q) {
        JsonElement atBlock = o.get("atBlock");
        if (atBlock != null && atBlock.isJsonPrimitive() && atBlock.getAsJsonPrimitive().isBoolean() && !atBlock.getAsBoolean()
                && !o.has("x")) {
            return false;
        }
        Long x = coord(o, "x");
        Long y = coord(o, "y");
        Long z = coord(o, "z");
        if (x == null || y == null || z == null) {
            x = coord(o, "blockX");
            y = coord(o, "blockY");
            z = coord(o, "blockZ");
        }
        if (x == null || y == null || z == null) {
            return false;
        }
        return within(x, q.minX(), q.maxX()) && within(y, q.minY(), q.maxY()) && within(z, q.minZ(), q.maxZ());
    }

    private static boolean within(long v, Integer min, Integer max) {
        return (min == null || v >= min) && (max == null || v <= max);
    }

    private static Long coord(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return (long) Math.floor(e.getAsDouble());
    }

    /**
     * The five header fields of a line, read by hand from the fixed start every line has, so lines that fail
     * the kind, player and world filters never reach Gson.
     */
    static final class Head {
        final String kind;
        final long at;
        final long seq;
        final String world;
        final String player;

        Head(String kind, long at, long seq, String world, String player) {
            this.kind = kind;
            this.at = at;
            this.seq = seq;
            this.world = world;
            this.player = player;
        }

        /** Null when the line does not start the way the journal writes it. */
        static Head parse(String s) {
            int[] p = {0};
            if (!lit(s, p, "{\"kind\":")) {
                return null;
            }
            String kind = str(s, p);
            if (kind == null || !lit(s, p, ",\"at\":")) {
                return null;
            }
            long at = num(s, p);
            if (at == Long.MIN_VALUE || !lit(s, p, ",\"seq\":")) {
                return null;
            }
            long seq = num(s, p);
            if (seq == Long.MIN_VALUE || !lit(s, p, ",\"world\":")) {
                return null;
            }
            String[] world = new String[1];
            if (!strOrNull(s, p, world) || !lit(s, p, ",\"player\":")) {
                return null;
            }
            String[] player = new String[1];
            if (!strOrNull(s, p, player)) {
                return null;
            }
            return new Head(kind, at, seq, world[0], player[0]);
        }

        /** Gson fallback for a line written in another field order. Null when it does not parse. */
        static Head slow(String line) {
            try {
                JsonElement e = JsonParser.parseString(line);
                if (!e.isJsonObject()) {
                    return null;
                }
                JsonObject o = e.getAsJsonObject();
                JsonElement k = o.get("kind");
                JsonElement at = o.get("at");
                if (k == null || !k.isJsonPrimitive() || at == null || !at.isJsonPrimitive()) {
                    return null;
                }
                JsonElement seq = o.get("seq");
                JsonElement w = o.get("world");
                JsonElement pl = o.get("player");
                return new Head(k.getAsString(), at.getAsLong(),
                        seq != null && seq.isJsonPrimitive() ? seq.getAsLong() : 0L,
                        w != null && w.isJsonPrimitive() ? w.getAsString() : null,
                        pl != null && pl.isJsonPrimitive() ? pl.getAsString() : null);
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private static boolean lit(String s, int[] p, String expect) {
            if (!s.startsWith(expect, p[0])) {
                return false;
            }
            p[0] += expect.length();
            return true;
        }

        private static String str(String s, int[] p) {
            int i = p[0];
            if (i >= s.length() || s.charAt(i) != '"') {
                return null;
            }
            int end = i + 1;
            while (end < s.length()) {
                char c = s.charAt(end);
                if (c == '\\') {
                    return null;
                }
                if (c == '"') {
                    p[0] = end + 1;
                    return s.substring(i + 1, end);
                }
                end++;
            }
            return null;
        }

        private static boolean strOrNull(String s, int[] p, String[] out) {
            if (s.startsWith("null", p[0])) {
                p[0] += 4;
                out[0] = null;
                return true;
            }
            out[0] = str(s, p);
            return out[0] != null;
        }

        private static long num(String s, int[] p) {
            int i = p[0];
            boolean neg = i < s.length() && s.charAt(i) == '-';
            if (neg) {
                i++;
            }
            int startDigits = i;
            long v = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') {
                    break;
                }
                v = v * 10 + (c - '0');
                i++;
            }
            if (i == startDigits || i - startDigits > 18) {
                return Long.MIN_VALUE;
            }
            p[0] = i;
            return neg ? -v : v;
        }
    }
}
