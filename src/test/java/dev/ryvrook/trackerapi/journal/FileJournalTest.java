package dev.ryvrook.trackerapi.journal;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.ServerLifecycle;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileJournalTest {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** 2026-10-14 10:00 UTC. */
    private static final long T0 = Instant.parse("2026-10-14T10:00:00Z").toEpochMilli();
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final Executor DIRECT = Runnable::run;

    @TempDir
    Path dir;
    private final AtomicLong clock = new AtomicLong(T0);
    private final AtomicLong seq = new AtomicLong();
    private FileJournal journal;

    @BeforeEach
    void setUp() {
        journal = journal(DIRECT, 14, 1024);
    }

    private FileJournal journal(Executor io, int days, int megabytes) {
        return new FileJournal(LOGGER, dir, io, null, new Perf(), clock::get, EnumSet.allOf(EventKind.class), days, megabytes);
    }

    private BlockBroken broken(long at, String world, UUID player, int x, int y, int z) {
        return new BlockBroken(new Header(at, seq.incrementAndGet(), world, player), x, y, z, "Rock_Stone", null, false, false);
    }

    private BlockPlaced placed(long at, String world, UUID player, int x, int y, int z) {
        return new BlockPlaced(new Header(at, seq.incrementAndGet(), world, player), x, y, z, "Rock_Stone", "Rock_Stone", false, false);
    }

    private Path hourFile(String world, long at, boolean gz) {
        return dir.resolve(world).resolve(FileJournal.fileName(at / HOUR) + (gz ? ".jsonl.gz" : ".jsonl"));
    }

    private static List<String> gunzipLines(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8).lines().toList();
        }
    }

    private List<Journal.Entry> find(Journal.Query q) {
        CompletableFuture<List<Journal.Entry>> f = journal.find(q);
        assertTrue(f.isDone(), "a direct executor answers at once");
        return f.join();
    }

    // ---- writing -----------------------------------------------------------------------------------------

    @Test
    void writesOneJsonLinePerEventWithTheHeaderFirst() throws IOException {
        journal.accept(broken(T0 + 1000, "default", STEVE, 1, 64, 2));
        journal.accept(broken(T0 + 2000, "default", STEVE, 3, 64, 4));
        journal.accept(new ServerLifecycle(new Header(T0 + 3000, seq.incrementAndGet(), null, null), ServerLifecycle.Phase.STARTED));
        assertEquals(3, journal.pending());
        journal.tick();
        assertEquals(0, journal.pending());
        assertEquals(3, journal.linesWritten());

        List<String> lines = Files.readAllLines(hourFile("default", T0, false));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("{\"kind\":\"block_broken\",\"at\":" + (T0 + 1000) + ",\"seq\":1,\"world\":\"default\",\"player\":\""
                + STEVE + "\",\"x\":1,"), lines.get(0));
        List<String> server = Files.readAllLines(hourFile(FileJournal.SERVER_FOLDER, T0, false));
        assertEquals(1, server.size());
        assertTrue(server.get(0).startsWith("{\"kind\":\"server_lifecycle\",\"at\":" + (T0 + 3000) + ",\"seq\":3,\"world\":null,\"player\":null,"),
                server.get(0));
    }

    @Test
    void wantsFollowsTheKindsAndReconfigure() {
        FileJournal j = new FileJournal(LOGGER, dir, DIRECT, null, null, clock::get, Set.of(EventKind.BLOCK_BROKEN), 14, 1024);
        assertTrue(j.wants(EventKind.BLOCK_BROKEN));
        assertFalse(j.wants(EventKind.BLOCK_PLACED));
        j.reconfigure(Set.of(EventKind.BLOCK_PLACED), 7, 64);
        assertFalse(j.wants(EventKind.BLOCK_BROKEN));
        assertTrue(j.wants(EventKind.BLOCK_PLACED));
        assertEquals(Set.of(EventKind.BLOCK_PLACED), j.kinds());
        j.stop();
        assertFalse(j.wants(EventKind.BLOCK_PLACED));
    }

    @Test
    void rollsTheHourAndGzipsTheClosedFile() throws IOException {
        journal.accept(broken(T0 + 10, "default", STEVE, 0, 0, 0));
        journal.accept(broken(T0 + 20, "default", STEVE, 0, 0, 0));
        journal.tick();
        assertTrue(Files.exists(hourFile("default", T0, false)));

        clock.set(T0 + HOUR + 5);
        journal.accept(broken(T0 + HOUR + 5, "default", STEVE, 0, 0, 0));
        journal.tick();
        assertFalse(Files.exists(hourFile("default", T0, false)));
        Path gz = hourFile("default", T0, true);
        assertTrue(Files.exists(gz));
        assertEquals(2, gunzipLines(gz).size());
        assertTrue(Files.exists(hourFile("default", T0 + HOUR, false)));

        // a quiet world is rolled by the clock alone
        clock.set(T0 + 2 * HOUR + 1);
        journal.tick();
        assertTrue(Files.exists(hourFile("default", T0 + HOUR, true)));
        assertFalse(Files.exists(hourFile("default", T0 + HOUR, false)));
    }

    @Test
    void recoverGzipsPlainFilesOfPassedHoursAndAppendsToAnExistingGzip() throws IOException {
        journal.accept(broken(T0 + 10, "default", STEVE, 0, 0, 0));
        journal.tick();
        journal.stop();
        assertTrue(Files.exists(hourFile("default", T0, false)));

        clock.set(T0 + 3 * HOUR);
        FileJournal next = journal(DIRECT, 14, 1024);
        next.start();
        assertTrue(Files.exists(hourFile("default", T0, true)));
        assertFalse(Files.exists(hourFile("default", T0, false)));

        // a late line of the same hour becomes a second gzip member
        Files.writeString(hourFile("default", T0, false),
                "{\"kind\":\"block_broken\",\"at\":" + (T0 + 50) + ",\"seq\":99,\"world\":\"default\",\"player\":null,\"x\":0,\"y\":0,\"z\":0}\n",
                StandardCharsets.UTF_8);
        next.gzip(hourFile("default", T0, false));
        assertEquals(2, gunzipLines(hourFile("default", T0, true)).size());
        assertTrue(next.bytesOnDisk() > 0);
    }

    @Test
    void stopWritesWhatIsPending() throws IOException {
        journal.accept(broken(T0 + 10, "default", STEVE, 0, 0, 0));
        journal.stop();
        assertEquals(1, Files.readAllLines(hourFile("default", T0, false)).size());
        journal.accept(broken(T0 + 20, "default", STEVE, 0, 0, 0));
        assertEquals(0, journal.pending(), "a stopped journal takes nothing");
    }

    @Test
    void dropsAndCountsPastTheQueueCapacity() throws IOException {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        FileJournal j = journal(tasks::add, 14, 1024);
        for (int i = 0; i < FileJournal.QUEUE_CAPACITY + 10; i++) {
            j.accept(broken(T0 + 10, "default", STEVE, i, 0, 0));
        }
        assertEquals(FileJournal.QUEUE_CAPACITY, j.pending());
        assertEquals(10, j.dropped());
        assertEquals(1, tasks.size(), "one early drain is asked for at 4,096 lines");
        while (!tasks.isEmpty()) {
            tasks.poll().run();
        }
        assertEquals(0, j.pending());
        assertEquals(FileJournal.QUEUE_CAPACITY, j.linesWritten());
        assertEquals(FileJournal.QUEUE_CAPACITY, Files.readAllLines(hourFile("default", T0, false)).size());
    }

    // ---- retention ---------------------------------------------------------------------------------------

    @Test
    void prunesFilesOlderThanTheRetention() throws IOException {
        journal = journal(DIRECT, 2, 1024);
        long old = T0 - TimeUnit.DAYS.toMillis(3);
        long recent = T0 - TimeUnit.DAYS.toMillis(1);
        clock.set(old);
        journal.accept(broken(old, "default", STEVE, 0, 0, 0));
        journal.tick();
        clock.set(recent);
        journal.accept(broken(recent, "default", STEVE, 0, 0, 0));
        journal.tick();
        clock.set(T0);
        journal.accept(broken(T0, "default", STEVE, 0, 0, 0));
        journal.tick();

        assertTrue(Files.exists(hourFile("default", old, true)));
        journal.prune();
        assertFalse(Files.exists(hourFile("default", old, true)));
        assertTrue(Files.exists(hourFile("default", recent, true)));
        assertTrue(Files.exists(hourFile("default", T0, false)));
        long expected = Files.size(hourFile("default", recent, true)) + Files.size(hourFile("default", T0, false));
        assertEquals(expected, journal.bytesOnDisk());
    }

    @Test
    void prunesOldestFirstUntilUnderTheSizeCap() throws IOException {
        journal = journal(DIRECT, 3650, 1);
        byte[] junk = new byte[400 * 1024];
        List<Path> files = new ArrayList<>();
        for (int h = 5; h >= 1; h--) {
            Path p = hourFile("default", T0 - h * HOUR, true);
            Files.createDirectories(p.getParent());
            Files.write(p, junk);
            files.add(p);
        }
        journal.prune();
        // five files of 400 KB against a 1 MB cap keep the newest two
        assertFalse(Files.exists(files.get(0)));
        assertFalse(Files.exists(files.get(1)));
        assertFalse(Files.exists(files.get(2)));
        assertTrue(Files.exists(files.get(3)));
        assertTrue(Files.exists(files.get(4)));
        assertEquals(2L * junk.length, journal.bytesOnDisk());
    }

    @Test
    void pruneNeverDeletesTheOpenFile() throws IOException {
        journal = journal(DIRECT, 3650, 1);
        StringBuilder big = new StringBuilder();
        big.repeat("x", 2 * 1024 * 1024);
        journal.accept(broken(T0, "default", STEVE, 0, 0, 0));
        journal.tick();
        Files.writeString(hourFile("default", T0, false), big, StandardOpenOption.APPEND);
        journal.prune();
        assertTrue(Files.exists(hourFile("default", T0, false)));
    }

    // ---- queries -----------------------------------------------------------------------------------------

    private void seed() {
        journal.accept(broken(T0 + 1_000, "default", STEVE, 10, 64, 10));
        journal.accept(placed(T0 + 2_000, "default", STEVE, 11, 64, 10));
        journal.accept(broken(T0 + 3_000, "default", ALEX, 500, 64, 500));
        journal.accept(broken(T0 + 4_000, "other", STEVE, 10, 64, 10));
        journal.tick();
        clock.set(T0 + HOUR + 1);
        journal.accept(broken(T0 + HOUR + 1_000, "default", STEVE, 12, 64, 10));
        journal.tick();
    }

    @Test
    void queriesNewestFirstAcrossGzippedAndOpenHours() {
        seed();
        assertTrue(Files.exists(hourFile("default", T0, true)));
        List<Journal.Entry> all = find(Journal.query());
        assertEquals(5, all.size());
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).at() >= all.get(i).at(), "newest first");
        }
        assertEquals(T0 + HOUR + 1_000, all.get(0).at());
        assertEquals(STEVE, all.get(0).player());
        assertEquals("default", all.get(0).world());
        assertEquals(EventKind.BLOCK_BROKEN, all.get(0).kind());
        assertTrue(all.get(0).json().startsWith("{\"kind\":\"block_broken\""));
    }

    @Test
    void queriesByKindPlayerWorldAndLimit() {
        seed();
        assertEquals(1, find(Journal.query().withKind(EventKind.BLOCK_PLACED)).size());
        assertEquals(1, find(Journal.query().withPlayer(ALEX)).size());
        assertEquals(4, find(Journal.query().withPlayer(STEVE)).size());
        assertEquals(1, find(Journal.query().withWorld("other")).size());
        assertEquals(4, find(Journal.query().withWorld("default")).size());
        assertEquals(0, find(Journal.query().withWorld("nowhere")).size());
        List<Journal.Entry> two = find(Journal.query().limit(2));
        assertEquals(2, two.size());
        assertEquals(T0 + HOUR + 1_000, two.get(0).at());
        assertEquals(T0 + 4_000, two.get(1).at());
    }

    @Test
    void queriesByBoxAndTime() {
        seed();
        List<Journal.Entry> near = find(Journal.query().withWorld("default").near(10, 64, 10, 2));
        assertEquals(3, near.size());
        assertTrue(near.stream().noneMatch(e -> e.player().equals(ALEX)));
        assertEquals(1, find(Journal.query().near(500, 64, 500, 0)).size());

        List<Journal.Entry> window = find(Journal.query().between(T0 + 1_500, T0 + 3_500));
        assertEquals(2, window.size());
        assertEquals(T0 + 3_000, window.get(0).at());
        assertEquals(T0 + 2_000, window.get(1).at());
        assertEquals(1, find(Journal.query().between(T0 + HOUR, Long.MAX_VALUE)).size());
    }

    @Test
    void boxSkipsEventsWithoutAPosition() {
        journal.accept(new ServerLifecycle(new Header(T0 + 1, seq.incrementAndGet(), null, null), ServerLifecycle.Phase.STARTED));
        journal.accept(new dev.ryvrook.trackerapi.api.event.ItemsGained(new Header(T0 + 2, seq.incrementAndGet(), "default", STEVE),
                "Wood", 1, 1, dev.ryvrook.trackerapi.api.event.ItemsGained.Source.values()[0], true, 5, 5, 5));
        journal.accept(new dev.ryvrook.trackerapi.api.event.ItemsGained(new Header(T0 + 3, seq.incrementAndGet(), "default", STEVE),
                "Wood", 1, 1, dev.ryvrook.trackerapi.api.event.ItemsGained.Source.values()[0], false, 0, 0, 0));
        journal.tick();
        List<Journal.Entry> box = find(Journal.query().near(0, 0, 0, 10));
        assertEquals(1, box.size(), "blockX fields count, atBlock false and no position do not");
        assertEquals(T0 + 2, box.get(0).at());
    }

    @Test
    void corruptLinesAreSkipped() throws IOException {
        journal.accept(broken(T0 + 1_000, "default", STEVE, 1, 1, 1));
        journal.tick();
        Path file = hourFile("default", T0, false);
        Files.writeString(file, "this is not json\n{\"kind\":\"block_broken\",\"at\":" + (T0 + 2_000)
                + ",\"seq\":9,\"world\":\"default\",\"player\":null,\"x\":1,\n\n", StandardOpenOption.APPEND);
        journal.accept(broken(T0 + 3_000, "default", STEVE, 1, 1, 1));
        journal.tick();
        List<Journal.Entry> all = find(Journal.query());
        assertEquals(2, all.size());
        assertEquals(2, journal.corruptLines());
    }

    @Test
    void manyQueriesAllComplete() {
        seed();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        FileJournal j = journal(tasks::add, 14, 1024);
        List<CompletableFuture<List<Journal.Entry>>> futures = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            futures.add(j.find(Journal.query()));
        }
        int guard = 0;
        while (!tasks.isEmpty() && guard++ < 10_000) {
            tasks.poll().run();
        }
        for (CompletableFuture<List<Journal.Entry>> f : futures) {
            assertTrue(f.isDone());
            assertEquals(5, f.join().size());
        }
    }

    @Test
    void handParserReadsTheHeaderAndRefusesOtherShapes() {
        FileJournal.Head h = FileJournal.Head.parse("{\"kind\":\"block_broken\",\"at\":12,\"seq\":3,\"world\":null,\"player\":\"" + STEVE + "\",\"x\":1}");
        assertNotNull(h);
        assertEquals("block_broken", h.kind);
        assertEquals(12, h.at);
        assertEquals(3, h.seq);
        assertNull(h.world);
        assertEquals(STEVE.toString(), h.player);
        assertNull(FileJournal.Head.parse("{\"at\":12,\"kind\":\"block_broken\"}"));
        assertNull(FileJournal.Head.parse("{\"kind\":\"block_broken\",\"at\":12,\"seq\":3,\"world\":\"we\\\"ird\",\"player\":null}"));
        FileJournal.Head slow = FileJournal.Head.slow("{\"at\":12,\"kind\":\"block_broken\",\"world\":\"w\"}");
        assertNotNull(slow);
        assertEquals("w", slow.world);
    }

    @Test
    void namesRoundTrip() {
        long hour = T0 / HOUR;
        assertEquals("2026-10-14-10", FileJournal.fileName(hour));
        assertEquals(hour, FileJournal.hourOf("2026-10-14-10.jsonl.gz"));
        assertEquals(-1, FileJournal.hourOf("notes.txt"));
        assertEquals("_server", FileJournal.folderName(null));
        assertEquals("my_world", FileJournal.folderName("my/world"));
        assertEquals("__server", FileJournal.folderName("_server"));
    }
}
