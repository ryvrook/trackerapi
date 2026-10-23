package dev.ryvrook.trackerapi.mods.melodies;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.counters.CounterRules;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ymmersive Melodies binding against a fake engine that hands out one sample per player and round, and a fake
 * mod whose song asset store holds Axel F (60 s) and Megalovania (90 s). The test drives the poll by hand, one
 * call of {@code scan} per second of play.
 */
class MelodiesBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID R = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final String LUTE = "Ymmersive_Melodies_Lute";
    private static final String DRUM = "Ymmersive_Melodies_Tiny_Drum";
    private static final MelodiesBinding.Names FAKE = new MelodiesBinding.Names("fakes.melodies");
    private static final MelodiesBinding.Names RENAMED = new MelodiesBinding.Names("fakes.melodies.renamed");
    private static final int FAILURES_TO_OFF = 20;

    /** One sample per player, all in the world "default". */
    static final class FakeEngine implements MelodiesBinding.Engine {
        final Map<UUID, MelodiesBinding.Sample> hands = new LinkedHashMap<>();
        boolean noReads;
        boolean throwInVisit;

        @Override
        public void check() {
            if (noReads) {
                throw new IllegalStateException("no item metadata");
            }
        }

        @Override
        public void scan(MelodiesBinding.WorldVisitor visitor) {
            if (throwInVisit) {
                visitor.visit("default", new AbstractList<>() {
                    @Override
                    public MelodiesBinding.Sample get(int index) {
                        throw new IllegalStateException("broken read");
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                });
                return;
            }
            visitor.visit("default", new ArrayList<>(hands.values()));
        }
    }

    @TempDir
    Path dir;
    private Services services;
    private FakeEngine engine;
    private MelodiesBinding binding;
    private ClassLoader loader;
    private Object plugin;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        String[] names = {"Alder", "Birch", "Cedar"};
        UUID[] ids = {P, Q, R};
        for (int i = 0; i < ids.length; i++) {
            services.counters.join(ids[i], names[i], 1L);
            services.players.join(ids[i], names[i], 1L).world = "default";
        }
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        engine = new FakeEngine();
        binding = new MelodiesBinding(engine, FAKE, 0);
        loader = FakeMods.loader();
        plugin = Class.forName("fakes.melodies.YmmersiveMelodies", true, loader).getConstructor().newInstance();
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
        services.publisher.close();
        services.timer.shutdownNow();
    }

    private void bind() {
        assertTrue(binding.bind(plugin, "0.0.1", services));
        seen.clear();
    }

    private void hold(UUID player, String item, String melody, long time, long start) {
        engine.hands.put(player, new MelodiesBinding.Sample(player, item, melody, time, start));
    }

    private void empty(UUID player) {
        engine.hands.put(player, new MelodiesBinding.Sample(player, null, "", 0, 0));
    }

    /** Plays a song on an item from {@code from} to {@code to} milliseconds, one scan per second. */
    private void play(UUID player, String item, String melody, long from, long to, long start) {
        for (long t = from; t <= to; t += 1000) {
            hold(player, item, melody, t, start);
            binding.scan();
        }
    }

    private long count(UUID player, String key) {
        return services.counters.get(player, key);
    }

    private <T> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private List<ModTally> tallies(String stat) {
        List<ModTally> out = new ArrayList<>();
        for (ModTally t : of(ModTally.class)) {
            if (t.stat().equals(stat)) {
                out.add(t);
            }
        }
        return out;
    }

    private String fact(UUID player) {
        return services.players.get(player).facts.get("melodies.playing");
    }

    @Test
    void fullBindListsBothFeaturesAndItsStats() throws Exception {
        assertEquals("melodies", binding.id());
        assertEquals("Conczin:Ymmersive Melodies", binding.pluginId());
        assertEquals("Ymmersive Melodies", binding.name());
        assertTrue(binding.bind(plugin, "0.0.1", services), "the manifest version 0.0.1 is no reason to refuse");
        assertEquals(List.of(MelodiesBinding.F_PLAYING, MelodiesBinding.F_LENGTHS), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("0.0.1", binding.version());
        assertEquals(Set.of(EventKind.MOD_TALLY, EventKind.MOD_MILESTONE), binding.kinds());
        assertNotSame(Class.forName("fakes.melodies.data.MelodyAsset"),
                Class.forName("fakes.melodies.data.MelodyAsset", false, loader), "the fake lives in its own loader");

        List<String> keys = new ArrayList<>();
        for (StatSpec s : binding.stats()) {
            keys.add(s.key() + (s.perId() ? ":<" + s.idLabel() + ">" : ""));
            assertTrue(Keys.isMod(s.key()), s.key());
            if (s.aggregate() == StatSpec.Aggregate.MAX) {
                assertTrue(CounterRules.isMax(s.key()), s.key());
            }
        }
        assertEquals(List.of("melodies:plays", "melodies:plays:<Song>", "melodies:finished", "melodies:finished:<Song>",
                "melodies:stopped", "melodies:seconds", "melodies:seconds:<Instrument>", "melodies:song_length_best",
                "melodies:band", "melodies:band_best", "melodies:song:<Song>", "melodies:songs",
                "melodies:instrument:<Instrument>", "melodies:instruments"), keys);

        ModBound bound = of(ModBound.class).get(0);
        assertEquals("melodies", bound.mod());
        assertTrue(bound.bound());
        assertEquals(2, bound.features().size());
    }

    @Test
    void aSongPlayedToItsEndIsStartedTimedAndFinished() {
        bind();
        play(P, LUTE, "Axel_F", 0, 0, 5_000);
        assertTrue(seen.isEmpty(), "the first sample is the baseline");
        play(P, LUTE, "Axel_F", 1_000, 1_000, 5_000);
        assertEquals(1, count(P, "melodies:plays"));
        assertEquals(1, count(P, "melodies:plays:Axel_F"));
        assertEquals(1, count(P, "melodies:song:Axel_F"));
        assertEquals(1, count(P, "melodies:songs"));
        assertEquals(1, count(P, "melodies:instrument:lute"));
        assertEquals(1, count(P, "melodies:instruments"));
        assertEquals(1, services.counters.server("melodies:plays"));
        assertEquals("Axel F", fact(P), "the name comes from the fake asset store");
        ModMilestone song = of(ModMilestone.class).stream().filter(m -> m.category().equals("song")).findFirst().orElseThrow();
        assertEquals("Axel F", song.name());

        play(P, LUTE, "Axel_F", 2_000, 59_000, 5_000);
        assertEquals(1, count(P, "melodies:plays"), "advancing is one start");
        assertEquals(30, count(P, "melodies:seconds"), "the first 30 s were published at once");
        assertEquals(1, tallies("seconds").size());
        // the song ends, the mod clears the song
        hold(P, LUTE, "", 0, 0);
        binding.scan();
        assertEquals(1, count(P, "melodies:finished"));
        assertEquals(1, count(P, "melodies:finished:Axel_F"));
        assertEquals(0, count(P, "melodies:stopped"));
        assertEquals(60, count(P, "melodies:song_length_best"));
        assertEquals(59, count(P, "melodies:seconds"));
        assertEquals(59, count(P, "melodies:seconds:lute"));
        assertNull(fact(P));
        assertTrue(of(ModTally.class).size() >= 4);
    }

    @Test
    void stopEarlyAndSwitchingSongsCountAsStopped() {
        bind();
        play(P, LUTE, "Megalovania", 0, 10_000, 7);
        hold(P, LUTE, "", 0, 0);
        binding.scan();
        assertEquals(1, count(P, "melodies:stopped"));
        assertEquals(0, count(P, "melodies:finished"));
        assertEquals(10, count(P, "melodies:seconds:lute"));

        play(P, LUTE, "Megalovania", 0, 3_000, 8);
        play(P, LUTE, "Axel_F", 0, 2_000, 9);
        assertEquals(2, count(P, "melodies:stopped"), "another song on the same instrument ends the first");
        assertEquals(3, count(P, "melodies:plays"));
        assertEquals(2, count(P, "melodies:plays:Megalovania"));
        assertEquals(2, count(P, "melodies:songs"));

        play(P, LUTE, "Axel_F", 500, 1_500, 9);
        assertEquals(3, count(P, "melodies:stopped"), "a restart of the same song ends it too");
        assertEquals(4, count(P, "melodies:plays"));
        assertEquals(0, count(P, "melodies:song_length_best"));
    }

    @Test
    void aPausedSongIsNotTimedAndResumesWithoutANewStart() {
        bind();
        play(P, LUTE, "Axel_F", 0, 5_000, 1);
        assertEquals(5_000, binding.pending(P, "lute"));
        empty(P);
        binding.scan();
        assertEquals(5, count(P, "melodies:seconds:lute"), "pausing publishes the time");
        binding.scan();
        play(P, LUTE, "Axel_F", 5_000, 5_000, 1);
        assertEquals(5, count(P, "melodies:seconds"), "held but not advancing");
        play(P, LUTE, "Axel_F", 6_000, 8_000, 1);
        assertEquals(1, count(P, "melodies:plays"));
        assertEquals(0, count(P, "melodies:stopped"));
        assertEquals(3_000, binding.pending(P, "lute"));
    }

    @Test
    void twoInstrumentsKeepTheirOwnSongs() {
        bind();
        play(P, LUTE, "Axel_F", 0, 3_000, 1);
        play(P, DRUM, "Megalovania", 0, 2_000, 2);
        play(P, LUTE, "Axel_F", 3_000, 5_000, 1);
        assertEquals(2, count(P, "melodies:plays"));
        assertEquals(0, count(P, "melodies:stopped"));
        assertEquals(2, count(P, "melodies:instruments"));
        assertEquals(1, count(P, "melodies:instrument:tiny_drum"));
        assertEquals(2, count(P, "melodies:seconds:tiny_drum"));
    }

    @Test
    void aJumpInTimeIsCappedAtTwoPeriods() {
        bind();
        play(P, LUTE, "Megalovania", 0, 1_000, 1);
        hold(P, LUTE, "Megalovania", 40_000, 1);
        binding.scan();
        assertEquals(3_000, binding.pending(P, "lute"));
    }

    @Test
    void aSongAlreadyPlayingAtTheFirstLookIsTimedButNotStarted() {
        bind();
        play(P, LUTE, "Axel_F", 20_000, 25_000, 1);
        assertEquals(0, count(P, "melodies:plays"));
        assertEquals(5_000, binding.pending(P, "lute"));
        hold(P, LUTE, "", 0, 0);
        binding.scan();
        assertEquals(1, count(P, "melodies:stopped"), "its end still counts");
    }

    @Test
    void uploadedSongsCountAsCustomAndAreNoDiscovery() {
        bind();
        String upload = P + ":My Song";
        play(P, LUTE, upload, 0, 21_000, 1);
        assertEquals(1, count(P, "melodies:plays:custom"));
        assertEquals(0, count(P, "melodies:songs"));
        assertEquals("My Song", fact(P));
        hold(P, LUTE, "", 0, 0);
        binding.scan();
        assertEquals(1, count(P, "melodies:finished:custom"), "without a length, 20 s of play is a finish");
        assertEquals(21, count(P, "melodies:song_length_best"));
        for (ModTally t : of(ModTally.class)) {
            assertTrue(t.id() == null || !t.id().contains(":"), "no uuid in a key");
        }
    }

    @Test
    void playersSharingAStartAndSongAreABand() {
        bind();
        for (long t = 0; t <= 3_000; t += 1000) {
            hold(P, LUTE, "Axel_F", t, 777);
            hold(Q, DRUM, "Axel_F", t, 777);
            hold(R, LUTE, "Megalovania", t, 777);
            binding.scan();
        }
        assertEquals(1, count(P, "melodies:band"));
        assertEquals(1, count(Q, "melodies:band"));
        assertEquals(2, count(P, "melodies:band_best"));
        assertEquals(0, count(R, "melodies:band"), "another song is another band");
        assertEquals(2, tallies("band").size());

        for (long t = 4_000; t <= 5_000; t += 1000) {
            hold(P, LUTE, "Axel_F", t, 777);
            hold(Q, DRUM, "Axel_F", t, 777);
            hold(R, LUTE, "Axel_F", t, 777);
            binding.scan();
        }
        assertEquals(3, count(P, "melodies:band_best"));
        assertEquals(1, count(P, "melodies:band"), "a growing band is still one song together");
        assertEquals(1, count(R, "melodies:band"));
        assertEquals(3, services.counters.server("melodies:band_best"));

        // a start time of zero is singleplayer mode, never a band
        empty(R);
        for (long t = 0; t <= 2_000; t += 1000) {
            hold(P, LUTE, "Megalovania", t, 0);
            hold(Q, DRUM, "Megalovania", t, 0);
            binding.scan();
        }
        assertEquals(1, count(P, "melodies:band"));
    }

    @Test
    void renamedSongStoreGivesAPartialBind() throws Exception {
        binding = new MelodiesBinding(engine, RENAMED, 0);
        Object renamed = Class.forName("fakes.melodies.renamed.YmmersiveMelodies", true, loader).getConstructor().newInstance();
        assertTrue(binding.bind(renamed, "0.0.1", services));
        assertEquals(List.of(MelodiesBinding.F_PLAYING), binding.features());
        assertEquals(List.of(MelodiesBinding.F_LENGTHS), binding.missing());
        seen.clear();
        play(P, LUTE, "Axel_F", 0, 12_000, 1);
        assertEquals("Axel F", fact(P), "the name from the id");
        hold(P, LUTE, "", 0, 0);
        binding.scan();
        assertEquals(1, count(P, "melodies:stopped"), "12 s without a length is a stop");
        play(P, LUTE, "Axel_F", 0, 25_000, 1);
        hold(P, LUTE, "", 0, 0);
        binding.scan();
        assertEquals(1, count(P, "melodies:finished"));
        assertEquals(25, count(P, "melodies:song_length_best"), "the last time seen");
    }

    @Test
    void anEngineWithoutTheReadsLeavesOnlyTheLengths() {
        engine.noReads = true;
        assertTrue(binding.bind(plugin, "0.0.1", services));
        assertEquals(List.of(MelodiesBinding.F_PLAYING), binding.missing());
        play(P, LUTE, "Axel_F", 0, 3_000, 1);
        assertEquals(0, count(P, "melodies:plays"));
    }

    @Test
    void aFailingReadIsGuardedAndSwitchesThePollOff() {
        bind();
        engine.throwInVisit = true;
        for (int i = 0; i < FAILURES_TO_OFF; i++) {
            binding.scan();
        }
        assertFalse(binding.features().contains(MelodiesBinding.F_PLAYING));
        assertTrue(binding.missing().contains(MelodiesBinding.F_PLAYING));
        assertTrue(binding.isBound(), "the lengths feature keeps the bind");
    }

    @Test
    void leavingAndUnbindForgetThePlayers() {
        bind();
        play(P, LUTE, "Axel_F", 0, 2_000, 1);
        play(Q, LUTE, "Axel_F", 0, 2_000, 1);
        assertEquals(2, binding.remembered());
        services.players.leave(Q);
        engine.hands.remove(Q);
        binding.scan();
        assertEquals(1, binding.remembered());

        binding.unbind("test");
        assertEquals(0, binding.remembered());
        assertNull(fact(P), "the facts go with the bind");
        seen.clear();
        play(P, LUTE, "Axel_F", 3_000, 5_000, 1);
        assertTrue(seen.isEmpty(), "nothing after unbind");
    }
}
