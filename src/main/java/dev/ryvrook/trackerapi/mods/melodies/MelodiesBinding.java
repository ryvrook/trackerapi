package dev.ryvrook.trackerapi.mods.melodies;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ymmersive Melodies. The mod has no API and fires no events. The song state is item metadata on the held
 * instrument ({@code MelodyProgress} with {@code Melody}, {@code Time} and {@code StartWorldTime}), which the mod
 * rewrites every tick while a song plays. A poll once a second reads that metadata through the engine for every
 * online player on the world thread and turns the changes between two samples into starts, ends, time played and
 * bands. The optional song lengths feature reads the built in songs' durations from the mod's asset store, so an
 * ended song can be told finished or stopped.
 *
 * <p>The engine reads sit behind {@link Engine}, {@link EngineReads} in production and a fake in tests.
 */
public final class MelodiesBinding extends AbstractBinding {
    public static final String ID = "melodies";
    public static final String PLUGIN_ID = "Conczin:Ymmersive Melodies";
    public static final String NAME = "Ymmersive Melodies";
    public static final String F_PLAYING = "songs played";
    public static final String F_LENGTHS = "song lengths";

    public static final String PLAYS = "plays";
    public static final String FINISHED = "finished";
    public static final String STOPPED = "stopped";
    public static final String SECONDS = "seconds";
    public static final String SONG_LENGTH = "song_length";
    public static final String BAND = "band";
    public static final String SONG = "song";
    public static final String INSTRUMENT = "instrument";

    /** The poll period in production. */
    public static final long DEFAULT_POLL = 1000;
    /** Time played is published per instrument once this much has gathered, and whenever a song pauses or ends. */
    static final long FLUSH_MILLIS = 30_000;
    /** Without a known length, an ended song counts as finished after this much playing. */
    static final long FALLBACK_FINISH_MILLIS = 20_000;
    /** Song lengths remembered at most, built in songs and asset pack songs together. */
    static final int SONG_CACHE_LIMIT = 512;

    /** The mod's package root, {@code net.conczin} in production, the fake's in tests. */
    public record Names(String root) {
        public static final Names PRODUCTION = new Names("net.conczin");

        String main() {
            return root + ".YmmersiveMelodies";
        }

        String asset() {
            return root + ".data.MelodyAsset";
        }

        String melody() {
            return root + ".data.Melody";
        }
    }

    /**
     * One player of a world at one poll. {@code itemId} is the held instrument or null when the hand holds anything
     * else, the song fields are then empty. {@code melody} is empty when no song is selected.
     */
    public record Sample(UUID player, String itemId, String melody, long time, long startWorldTime) {
    }

    /** Receives every player of one world, on that world's thread. */
    @FunctionalInterface
    public interface WorldVisitor {
        void visit(String world, List<Sample> samples);
    }

    /** The engine reads of this binding. The production one is {@link EngineReads}, tests give a fake. */
    public interface Engine {
        /** Throws when the engine lacks the hand or item metadata reads. Called once at bind. */
        void check() throws Throwable;

        /** Posts a read to every world with players and returns at once. The visitor runs on each world thread. */
        void scan(WorldVisitor visitor);
    }

    /** A song of one instrument item as last seen. */
    static final class Run {
        String melody = "";
        long time;
        long start;
        boolean started;
        long playedMillis;
        boolean band;
        int bandBest;

        void reset(String melody, long time, long start) {
            this.melody = melody;
            this.time = time;
            this.start = start;
            started = false;
            playedMillis = 0;
            band = false;
            bandBest = 0;
        }
    }

    /** One player's state, touched on the player's world thread. */
    static final class PlayerState {
        final Map<String, Run> runs = new HashMap<>(4);
        final Map<String, Long> pending = new HashMap<>(4);
        String playing;
        String lastItem;
    }

    /** A built in song's name and length in milliseconds, the length is -1 when unknown. */
    record Song(String name, long lengthMillis) {
    }

    private final Engine engine;
    private final Names names;
    private final long pollMillis;
    private final long period;
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<String, Song> songs = new ConcurrentHashMap<>();
    private final Map<Class<?>, MethodHandle> byClass = new ConcurrentHashMap<>();
    private volatile MethodHandle getAssetStore;
    private volatile MethodHandle getMelody;
    private volatile MethodHandle duration;
    private volatile MethodHandle melodyName;

    /** The production binding, polling once a second. */
    public MelodiesBinding() {
        this(new EngineReads(), Names.PRODUCTION, DEFAULT_POLL);
    }

    /** A binding over the given engine reads. A {@code pollMillis} of zero or less runs no timer, tests call {@link #scan}. */
    public MelodiesBinding(Engine engine, Names names, long pollMillis) {
        super(ID, PLUGIN_ID, NAME);
        this.engine = engine;
        this.names = names;
        this.pollMillis = pollMillis;
        this.period = pollMillis > 0 ? pollMillis : DEFAULT_POLL;
    }

    static String key(String stat) {
        return ModKeys.key(ID, stat);
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_TALLY, EventKind.MOD_MILESTONE);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(key(PLAYS), "Songs started"),
                StatSpec.perId(key(PLAYS), "Plays by song", "Song"),
                StatSpec.sum(key(FINISHED), "Songs finished"),
                StatSpec.perId(key(FINISHED), "Finished by song", "Song"),
                StatSpec.sum(key(STOPPED), "Songs stopped early"),
                StatSpec.sum(key(SECONDS), "Time played").unit("seconds"),
                StatSpec.perId(key(SECONDS), "Time by instrument", "Instrument").unit("seconds"),
                StatSpec.max(key(SONG_LENGTH + "_best"), "Longest song finished").unit("seconds"),
                StatSpec.sum(key(BAND), "Songs played together"),
                StatSpec.max(key(BAND + "_best"), "Largest band"),
                StatSpec.discovery(key(SONG), "Songs played", "Song"),
                StatSpec.count(key(ModKeys.plural(SONG)), "Distinct songs played"),
                StatSpec.discovery(key(INSTRUMENT), "Instruments played", "Instrument"),
                StatSpec.count(key(ModKeys.plural(INSTRUMENT)), "Distinct instruments played"));
    }

    @Override
    protected void resolve(Object plugin) {
        feature(F_PLAYING, () -> {
            // The mod's presence through its own loader, nothing of it is called for this feature.
            Handles.statikByName(Handles.type(loader, names.main()), "getInstance", 0);
            engine.check();
            players.clear();
            if (pollMillis > 0) {
                ScheduledFuture<?> poll = services.timer.scheduleAtFixedRate(() -> guard(F_PLAYING, this::scan),
                        pollMillis, pollMillis, TimeUnit.MILLISECONDS);
                onUnbind(() -> poll.cancel(false));
            }
            onUnbind(players::clear);
        });
        feature(F_LENGTHS, () -> {
            Class<?> asset = Handles.type(loader, names.asset());
            Class<?> melody = Handles.type(loader, names.melody());
            getAssetStore = Handles.statikByName(asset, "getAssetStore", 0);
            getMelody = Handles.virtualByName(asset, "getMelody", 0);
            duration = Handles.virtualByName(melody, "duration", 0);
            melodyName = Handles.virtualByName(melody, "name", 0);
            onUnbind(this::forgetLengths);
        });
    }

    private void forgetLengths() {
        getAssetStore = null;
        getMelody = null;
        duration = null;
        melodyName = null;
        songs.clear();
        byClass.clear();
    }

    @Override
    protected void reset() {
        players.clear();
        songs.clear();
    }

    // ---- poll --------------------------------------------------------------------------------------------

    /** One round of the poll, the timer thread. Forgets players who left and posts the reads to the worlds. */
    void scan() {
        if (!has(F_PLAYING)) {
            return;
        }
        players.keySet().removeIf(u -> !online(u));
        engine.scan((world, samples) -> guard(F_PLAYING, () -> visit(world, samples)));
    }

    /** Every player of one world, its thread. */
    private void visit(String world, List<Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        Map<String, List<Sample>> bands = null;
        for (int i = 0, n = samples.size(); i < n; i++) {
            Sample s = samples.get(i);
            if (s == null || s.player() == null || !online(s.player())) {
                continue;
            }
            if (step(world, s) && s.startWorldTime() != 0) {
                if (bands == null) {
                    bands = new HashMap<>();
                }
                bands.computeIfAbsent(s.melody() + '\n' + s.startWorldTime(), k -> new ArrayList<>(2)).add(s);
            }
        }
        if (bands == null) {
            return;
        }
        for (List<Sample> group : bands.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (Sample s : group) {
                band(world, s, group.size());
            }
        }
    }

    /** One player's sample. Returns true when the player's song advanced since the last sample. */
    private boolean step(String world, Sample s) {
        UUID uuid = s.player();
        PlayerState ps = players.computeIfAbsent(uuid, u -> new PlayerState());
        synchronized (ps) {
            String inst = Melodies.instrumentKey(s.itemId());
            if (inst == null) {
                ps.lastItem = null;
                flushAll(world, uuid, ps);
                playing(uuid, ps, null);
                return false;
            }
            if (!s.itemId().equals(ps.lastItem)) {
                flushAll(world, uuid, ps); // another instrument in hand, the last one's time is done
                ps.lastItem = s.itemId();
            }
            String melody = s.melody() == null ? "" : s.melody();
            Run run = ps.runs.get(s.itemId());
            if (run == null) {
                // The first look at this item. A song already well into its time was started before the binding saw it.
                run = new Run();
                run.reset(melody, s.time(), s.startWorldTime());
                run.started = !melody.isEmpty() && s.time() > 2 * period;
                ps.runs.put(s.itemId(), run);
                return false;
            }
            if (melody.isEmpty() || !melody.equals(run.melody) || s.time() < run.time) {
                if (run.started) {
                    ended(world, uuid, inst, run, melody.isEmpty());
                }
                flush(world, uuid, ps, inst);
                playing(uuid, ps, null);
                run.reset(melody, s.time(), s.startWorldTime());
                return false;
            }
            long delta = s.time() - run.time;
            run.time = s.time();
            run.start = s.startWorldTime();
            if (delta <= 0) {
                flush(world, uuid, ps, inst); // paused, the item kept its metadata
                playing(uuid, ps, null);
                return false;
            }
            if (!run.started) {
                run.started = true;
                started(world, uuid, inst, melody);
            }
            run.playedMillis += delta;
            // The cap ignores the jump of a sync join or a world change.
            long add = Math.min(delta, 2 * period);
            long now = ps.pending.merge(inst, add, Long::sum);
            if (now >= FLUSH_MILLIS) {
                flush(world, uuid, ps, inst);
            }
            playing(uuid, ps, songName(melody));
            return true;
        }
    }

    private void started(String world, UUID uuid, String inst, String melody) {
        if (wants(EventKind.MOD_TALLY)) {
            publish(new ModTally(header(world, uuid), ID, PLAYS, Melodies.songKey(melody), 1, 0));
        }
        if (wants(EventKind.MOD_MILESTONE)) {
            publish(new ModMilestone(header(world, uuid), ID, INSTRUMENT, inst, Melodies.nameOf(inst)));
            if (!Melodies.isUploaded(melody)) {
                publish(new ModMilestone(header(world, uuid), ID, SONG, melody, songName(melody)));
            }
        }
    }

    /**
     * A started song left the item, by its end, by Stop, or by another song or a restart. Only an empty song can
     * be a finish. With a known length the last time seen must be within two periods of it, without one the song
     * must have played {@link #FALLBACK_FINISH_MILLIS}.
     */
    private void ended(String world, UUID uuid, String inst, Run run, boolean cleared) {
        if (!wants(EventKind.MOD_TALLY)) {
            return;
        }
        long length = Melodies.isUploaded(run.melody) ? -1 : length(run.melody);
        boolean finished;
        if (!cleared) {
            finished = false;
        } else if (length > 0) {
            finished = run.time >= length - Math.max(1500, 2 * period);
        } else {
            finished = run.playedMillis >= FALLBACK_FINISH_MILLIS;
        }
        if (finished) {
            publish(new ModTally(header(world, uuid), ID, FINISHED, Melodies.songKey(run.melody), 1, 0));
            long seconds = (length > 0 ? length : run.time) / 1000;
            if (seconds > 0) {
                publish(new ModTally(header(world, uuid), ID, SONG_LENGTH, null, 0, seconds));
            }
        } else {
            publish(new ModTally(header(world, uuid), ID, STOPPED, null, 1, 0));
        }
    }

    /** A player whose song shares its start and melody with others in the same world, its world thread. */
    private void band(String world, Sample s, int size) {
        PlayerState ps = players.get(s.player());
        if (ps == null) {
            return;
        }
        synchronized (ps) {
            Run run = ps.runs.get(s.itemId());
            if (run == null || !run.started) {
                return;
            }
            if (!run.band) {
                run.band = true;
                run.bandBest = size;
                if (wants(EventKind.MOD_TALLY)) {
                    publish(new ModTally(header(world, s.player()), ID, BAND, null, 1, size));
                }
            } else if (size > run.bandBest) {
                run.bandBest = size;
                if (wants(EventKind.MOD_TALLY)) {
                    publish(new ModTally(header(world, s.player()), ID, BAND, null, 0, size));
                }
            }
        }
    }

    /** Publishes the whole seconds gathered on one instrument and keeps the rest. */
    private void flush(String world, UUID uuid, PlayerState ps, String inst) {
        Long ms = ps.pending.get(inst);
        if (ms == null) {
            return;
        }
        long seconds = ms / 1000;
        if (seconds <= 0) {
            return;
        }
        ps.pending.put(inst, ms - seconds * 1000);
        if (wants(EventKind.MOD_TALLY)) {
            publish(new ModTally(header(world, uuid), ID, SECONDS, inst, seconds, 0));
        }
    }

    private void flushAll(String world, UUID uuid, PlayerState ps) {
        if (ps.pending.isEmpty()) {
            return;
        }
        for (String inst : List.copyOf(ps.pending.keySet())) {
            flush(world, uuid, ps, inst);
        }
    }

    private void playing(UUID uuid, PlayerState ps, String name) {
        if (name == null ? ps.playing == null : name.equals(ps.playing)) {
            return;
        }
        ps.playing = name;
        fact(uuid, "playing", name);
    }

    // ---- song lengths ------------------------------------------------------------------------------------

    private String songName(String melody) {
        if (Melodies.isUploaded(melody)) {
            int at = melody.indexOf(':');
            return melody.substring(at + 1);
        }
        Song song = song(melody);
        return song != null && song.name() != null ? song.name() : Melodies.nameOf(melody);
    }

    private long length(String melody) {
        Song song = song(melody);
        return song == null ? -1 : song.lengthMillis();
    }

    /** A built in song from the mod's asset store, the world thread, cached per id. Null without the feature. */
    private Song song(String id) {
        if (id == null || id.isEmpty() || !has(F_LENGTHS)) {
            return null;
        }
        Song hit = songs.get(id);
        if (hit != null) {
            return hit;
        }
        Song[] out = new Song[1];
        guard(F_LENGTHS, () -> out[0] = readSong(id));
        if (out[0] != null && songs.size() < SONG_CACHE_LIMIT) {
            songs.put(id, out[0]);
        }
        return out[0];
    }

    private Song readSong(String id) throws Throwable {
        MethodHandle store = getAssetStore;
        MethodHandle melodyOf = getMelody;
        MethodHandle lengthOf = duration;
        MethodHandle nameOf = melodyName;
        if (store == null || melodyOf == null || lengthOf == null || nameOf == null) {
            return null;
        }
        Object assets = store.invoke();
        if (assets == null) {
            return null;
        }
        Object map = handle(assets.getClass(), "getAssetMap", 0).invoke(assets);
        if (map == null) {
            return null;
        }
        Object asset = handle(map.getClass(), "getAsset", 1).invoke(map, (Object) id);
        if (asset == null) {
            return new Song(Melodies.nameOf(id), -1);
        }
        Object melody = melodyOf.invoke(asset);
        if (melody == null) {
            return new Song(Melodies.nameOf(id), -1);
        }
        String name = Handles.stringOf(nameOf.invoke(melody));
        long length = Handles.longOf(lengthOf.invoke(melody), -1);
        return new Song(name == null || name.isBlank() ? Melodies.nameOf(id) : name, length);
    }

    /** One getter per receiver class, the store and the map are different classes. */
    private MethodHandle handle(Class<?> owner, String name, int params) throws ReflectiveOperationException {
        MethodHandle mh = byClass.get(owner);
        if (mh == null) {
            mh = Handles.virtualByName(owner, name, params);
            byClass.put(owner, mh);
        }
        return mh;
    }

    // ---- for tests ---------------------------------------------------------------------------------------

    /** How many players have state, for tests. */
    int remembered() {
        return players.size();
    }

    /** The milliseconds of play gathered and not yet published for a player's instrument, for tests. */
    long pending(UUID player, String instrument) {
        PlayerState ps = players.get(player);
        if (ps == null) {
            return 0;
        }
        synchronized (ps) {
            return ps.pending.getOrDefault(instrument, 0L);
        }
    }
}
