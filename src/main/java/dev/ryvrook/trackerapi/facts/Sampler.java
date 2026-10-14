package dev.ryvrook.trackerapi.facts;

import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.builtin.adventure.objectives.components.ObjectiveHistoryComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.event.MemoryRecorded;
import dev.ryvrook.trackerapi.api.event.ObjectiveCompleted;
import dev.ryvrook.trackerapi.api.event.PlayerMoved;
import dev.ryvrook.trackerapi.api.event.PlayerSlept;
import dev.ryvrook.trackerapi.api.event.RecipeLearned;
import dev.ryvrook.trackerapi.api.event.ReputationChanged;
import dev.ryvrook.trackerapi.counters.PlayerCounters;
import dev.ryvrook.trackerapi.util.Guard;
import dev.ryvrook.trackerapi.util.Perf;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The once a second look at every online player. The {@code TrackerAPI-timer} thread counts playtime,
 * sweeps the players who left more than an hour ago and posts one task per world with players through a
 * {@link WorldTasks.Gate}, so a lagging world skips a round instead of queueing them. The world task reads
 * each player's transform, movement states, mount, zone and biome, memories and sleep, and every five
 * seconds their objectives, known recipes and reputation. It writes the player's
 * {@link PlayerRegistry.Online#snapshot} and publishes PLAYER_MOVED, MEMORY_RECORDED, PLAYER_SLEPT,
 * OBJECTIVE_COMPLETED, RECIPE_LEARNED and REPUTATION_CHANGED.
 *
 * <p>Distance comes from the player's transform. Swimming and gliding come from the movement states the
 * client reports. Riding is a {@link MountedComponent} that points at an entity, a mount or a minecart, and
 * the ridden distance is measured from the mount's own position with a second {@link MovementSampler}, so
 * it counts whether or not the rider's transform follows the mount. While riding, the sample is published
 * as a RIDE step and the rider's own walk is not counted on top of it.
 *
 * <p>Memories, objectives, recipes and reputation are diffed against what the sampler saw last. The first
 * read of a session waits until the player's counters are read from disk and then publishes only what
 * the counters do not know yet, so a relog never repeats old discoveries and a player who met something
 * before TrackerAPI was installed still gets it counted once.
 *
 * <p>Every optional read sits in its own guard. A LinkageError turns that read off with one warning, so
 * a reshaped engine class loses one feature rather than the whole sample.
 */
public final class Sampler {
    /** Objectives, recipes and reputation are read this often. */
    private static final long SLOW_MILLIS = 5_000;
    /** The anti farm memory of a player outlives a relog by this long. */
    public static final long OFFLINE_KEEP_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final double RAD_TO_DEG = 180.0 / Math.PI;
    private static final int UNSEEN = Integer.MIN_VALUE;

    private final Services s;
    private final HytaleLogger logger;
    private final MovementSampler moves = new MovementSampler();
    private final MovementSampler rides = new MovementSampler();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> offlineSince = new ConcurrentHashMap<>();
    private final WorldTasks.Gate gate = new WorldTasks.Gate(10_000);
    private volatile ScheduledFuture<?> task;
    private int sampleSeconds = 1;
    private int slowEvery = 5;
    private long rounds;

    private volatile boolean statesOff;
    private volatile boolean mountsOff;
    private volatile boolean mapOff;
    private volatile boolean memoriesOff;
    private volatile boolean objectivesOff;
    private volatile boolean recipesOff;
    private volatile boolean reputationOff;
    private volatile ComponentType<EntityStore, MountedComponent> mountedType;
    private volatile ComponentType<EntityStore, ObjectiveHistoryComponent> historyType;

    /** What the sampler saw of one player last time. Touched by the world thread that samples them. */
    private static final class State {
        Ref<EntityStore> lastMount;
        boolean sleepKnown;
        boolean asleep;
        int memoryHash = UNSEEN;
        Set<String> memories;
        Set<String> objectives;
        Set<String> recipes;
        Map<String, Integer> reputation;
    }

    public Sampler(Services services, HytaleLogger logger) {
        this.s = services;
        this.logger = logger;
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Starts the timer. The interval is read once, a change applies at the next restart. */
    public void start() {
        sampleSeconds = Math.max(1, s.config().sampleSeconds);
        slowEvery = (int) Math.max(1, SLOW_MILLIS / TimeUnit.SECONDS.toMillis(sampleSeconds));
        task = s.timer.scheduleAtFixedRate(this::tick, sampleSeconds, sampleSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
        }
        task = null;
    }

    /** The player is in a new world or teleported, so the next sample starts a fresh step. */
    public void resetPosition(UUID uuid) {
        moves.resetPosition(uuid);
        rides.resetPosition(uuid);
        State st = uuid == null ? null : states.get(uuid);
        if (st != null) {
            synchronized (st) {
                st.lastMount = null;
            }
        }
    }

    /** The player is back, so their anti farm memory is not forgotten. */
    public void markOnline(UUID uuid) {
        if (uuid != null) {
            offlineSince.remove(uuid);
        }
    }

    /**
     * The player left. Their sampler state goes now, the anti farm memory of every fact is forgotten
     * through {@link Forgetting} once they stayed away for {@link #OFFLINE_KEEP_MILLIS}.
     */
    public void left(UUID uuid, long now) {
        if (uuid == null) {
            return;
        }
        moves.forget(uuid);
        rides.forget(uuid);
        states.remove(uuid);
        offlineSince.put(uuid, now);
    }

    /** The players waiting for their anti farm memory to be forgotten, for tests and status. */
    public int offlineCount() {
        return offlineSince.size();
    }

    /** Forgets the gate entry of a removed world. */
    public void forgetWorld(World world) {
        gate.forget(world);
    }

    // ---- timer thread ---------------------------------------------------------------------------------

    private void tick() {
        long start = System.nanoTime();
        try {
            long now = System.currentTimeMillis();
            for (PlayerRegistry.Online o : s.players.all()) {
                s.counters.add(o.uuid, Keys.PLAYTIME, sampleSeconds, o.world);
            }
            sweepOffline(now);
            boolean slow = ++rounds % slowEvery == 0;
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                try {
                    if (world == null || world.getPlayerCount() <= 0) {
                        continue;
                    }
                    gate.post(world, () -> sampleWorld(world, slow));
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(Perf.R_SAMPLER, System.nanoTime() - start);
        }
    }

    /** Forgets everything about players who have been gone for more than an hour. */
    void sweepOffline(long now) {
        offlineSince.entrySet().removeIf(e -> {
            if (now - e.getValue() <= OFFLINE_KEEP_MILLIS) {
                return false;
            }
            s.forgetting.forget(e.getKey());
            return true;
        });
    }

    // ---- world thread ---------------------------------------------------------------------------------

    private void sampleWorld(World world, boolean slow) {
        long start = System.nanoTime();
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            String worldName = world.getName();
            long now = System.currentTimeMillis();
            for (PlayerRef pr : world.getPlayerRefs()) {
                try {
                    samplePlayer(pr, store, worldName, now, slow);
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(Perf.R_SAMPLER, System.nanoTime() - start);
        }
    }

    private void samplePlayer(PlayerRef pr, Store<EntityStore> store, String worldName, long now, boolean slow) {
        if (pr == null) {
            return;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return; // moved to another world since the list was read, sampled there next time
        }
        UUID uuid = pr.getUuid();
        PlayerRegistry.Online o = s.players.get(uuid);
        if (o == null) {
            return; // not ready yet
        }
        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d pos = tc == null ? null : tc.getPosition();
        if (pos == null || Double.isNaN(pos.x) || Double.isNaN(pos.y) || Double.isNaN(pos.z)) {
            return;
        }
        Rotation3f rot = tc.getRotation();
        float yaw = rot == null ? 0f : (float) (rot.yaw() * RAD_TO_DEG);
        float pitch = rot == null ? 0f : (float) (rot.pitch() * RAD_TO_DEG);
        State st = states.computeIfAbsent(uuid, id -> new State());
        MovementStates ms = movementStates(ref, store);
        boolean swimming = ms != null && ms.swimming;
        boolean gliding = ms != null && ms.gliding;

        Player player = store.getComponent(ref, Player.getComponentType());
        String zone = null;
        String region = null;
        String biome = null;
        if (!mapOff && player != null) {
            try {
                WorldMapTracker map = player.getWorldMapTracker();
                if (map != null) {
                    WorldMapTracker.ZoneDiscoveryInfo info = map.getCurrentZone();
                    if (info != null) {
                        zone = info.zoneName();
                        region = info.regionName();
                    }
                    biome = map.getCurrentBiomeName();
                }
            } catch (LinkageError e) {
                mapOff = true;
                logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read zones and biomes on this server, they stay empty");
            }
        }

        boolean riding;
        synchronized (st) {
            riding = move(uuid, ref, store, st, pos, swimming, gliding, worldName, zone, biome);
            if (ms != null) {
                sleep(uuid, st, ms.sleeping, pos, worldName);
            }
            PlayerCounters pc = s.counters.live(uuid);
            boolean baselineReady = pc != null && pc.isMerged();
            if (baselineReady && !memoriesOff) {
                memories(uuid, ref, store, st, worldName);
            }
            if (slow && baselineReady) {
                objectives(uuid, ref, store, st, worldName);
                if (player != null) {
                    configData(uuid, player, st, worldName);
                }
            }
        }

        o.lastX = pos.x;
        o.lastY = pos.y;
        o.lastZ = pos.z;
        o.placed = true;
        if (worldName != null && !worldName.equals(o.world)) {
            o.world = worldName;
        }
        o.snapshot = new PlayerSnapshot(uuid, o.name, worldName, pos.x, pos.y, pos.z, yaw, pitch, o.gameMode,
                zone, region, biome, o.creative, swimming, gliding, riding, o.sessionStart, now);
    }

    // ---- movement ---------------------------------------------------------------------------------------

    /** Samples the walk and the ride, publishes the step. Returns true while riding. */
    private boolean move(UUID uuid, Ref<EntityStore> ref, Store<EntityStore> store, State st, Vector3d pos,
                         boolean swimming, boolean gliding, String world, String zone, String biome) {
        Ref<EntityStore> mountRef = mountOf(ref, store);
        Vector3d mount = mountRef != null ? positionOf(mountRef, store) : null;
        MovementSampler.Step step;
        if (mount == null) {
            if (st.lastMount != null) {
                rides.resetPosition(uuid);
                st.lastMount = null;
            }
            MovementSampler.Mode mode = gliding ? MovementSampler.Mode.GLIDE
                    : swimming ? MovementSampler.Mode.SWIM : MovementSampler.Mode.WALK;
            step = moves.sample(uuid, pos.x, pos.y, pos.z, mode);
        } else {
            if (!mountRef.equals(st.lastMount)) {
                rides.resetPosition(uuid); // a different mount, so its first sample only sets the start
                st.lastMount = mountRef;
            }
            moves.resetPosition(uuid); // the rider's own walk starts afresh once they get off
            step = rides.sample(uuid, mount.x, mount.y, mount.z, MovementSampler.Mode.RIDE);
        }
        if (step.moved() && s.publisher.wants(EventKind.PLAYER_MOVED)) {
            s.publisher.publish(new PlayerMoved(s.header(world, uuid), pos.x, pos.y, pos.z, step.metres(),
                    modeOf(step.mode()), zone, biome));
        }
        return mount != null;
    }

    private static PlayerMoved.Mode modeOf(MovementSampler.Mode mode) {
        return switch (mode) {
            case SWIM -> PlayerMoved.Mode.SWIM;
            case GLIDE -> PlayerMoved.Mode.GLIDE;
            case RIDE -> PlayerMoved.Mode.RIDE;
            default -> PlayerMoved.Mode.WALK;
        };
    }

    private MovementStates movementStates(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (statesOff) {
            return null;
        }
        try {
            MovementStatesComponent c = store.getComponent(ref, MovementStatesComponent.getComponentType());
            return c == null ? null : c.getMovementStates();
        } catch (LinkageError e) {
            statesOff = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read movement states on this server, swim and glide distance and sleep are off");
            return null;
        }
    }

    /** The entity the player rides, or null when they ride nothing that moves. */
    private Ref<EntityStore> mountOf(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, MountedComponent> type = mountedType();
        if (type == null) {
            return null;
        }
        try {
            MountedComponent mounted = store.getComponent(ref, type);
            Ref<EntityStore> mount = mounted != null ? mounted.getMountedToEntity() : null;
            return mount != null && mount.isValid() && mount.getStore() == store ? mount : null;
        } catch (LinkageError e) {
            mountsOff = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read mounts on this server, ride distance is off");
            return null;
        }
    }

    private static Vector3d positionOf(Ref<EntityStore> mount, Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(mount, TransformComponent.getComponentType());
        return transform != null ? transform.getPosition() : null;
    }

    /** The mounted component type, looked up once. The mounts plugin owns it, a server without it has none. */
    private ComponentType<EntityStore, MountedComponent> mountedType() {
        ComponentType<EntityStore, MountedComponent> type = mountedType;
        if (type != null || mountsOff) {
            return type;
        }
        try {
            type = MountedComponent.getComponentType();
        } catch (RuntimeException | LinkageError e) {
            type = null;
        }
        if (type == null) {
            mountsOff = true;
            logger.at(Level.WARNING).log("TrackerAPI cannot read mounts on this server, ride distance is off");
            return null;
        }
        mountedType = type;
        return type;
    }

    // ---- sleep ------------------------------------------------------------------------------------------

    private void sleep(UUID uuid, State st, boolean asleep, Vector3d pos, String world) {
        if (!st.sleepKnown) {
            st.sleepKnown = true;
            st.asleep = asleep;
            return;
        }
        if (st.asleep == asleep) {
            return;
        }
        st.asleep = asleep;
        if (s.publisher.wants(EventKind.PLAYER_SLEPT)) {
            s.publisher.publish(new PlayerSlept(s.header(world, uuid), asleep, (int) Math.floor(pos.x),
                    (int) Math.floor(pos.y), (int) Math.floor(pos.z)));
        }
    }

    // ---- memories ---------------------------------------------------------------------------------------

    private void memories(UUID uuid, Ref<EntityStore> ref, Store<EntityStore> store, State st, String world) {
        try {
            PlayerMemories memories = store.getComponent(ref, PlayerMemories.getComponentType());
            Set<Memory> recorded = memories == null ? null : memories.getRecordedMemories();
            if (recorded == null) {
                return;
            }
            int sum = 0;
            int count = 0;
            for (Memory m : recorded) {
                String id = m == null ? null : m.getId();
                if (id != null) {
                    sum += id.hashCode();
                    count++;
                }
            }
            int hash = sum * 31 + count;
            if (hash == st.memoryHash) {
                return; // nothing changed, no set built
            }
            st.memoryHash = hash;
            Set<String> ids = new HashSet<>(Math.max(4, count * 2));
            for (Memory m : recorded) {
                String id = m == null ? null : m.getId();
                if (id != null) {
                    ids.add(id);
                }
            }
            Set<String> before = st.memories;
            st.memories = ids;
            for (String id : ids) {
                if (isNew(uuid, before, Keys.P_MEMORY, id) && s.publisher.wants(EventKind.MEMORY_RECORDED)) {
                    s.publisher.publish(new MemoryRecorded(s.header(world, uuid), id));
                }
            }
        } catch (LinkageError e) {
            memoriesOff = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read Memories on this server, MEMORY_RECORDED is off");
        }
    }

    /**
     * True when an id is worth an event. After the first read it is any id the last read did not have.
     * On the first read of a session it is an id the player's counters do not know yet.
     */
    private boolean isNew(UUID uuid, Set<String> before, String prefix, String id) {
        if (before != null) {
            return !before.contains(id);
        }
        return s.counters.get(uuid, Keys.of(prefix, id)) == 0;
    }

    // ---- objectives, recipes, reputation ---------------------------------------------------------------

    private void objectives(UUID uuid, Ref<EntityStore> ref, Store<EntityStore> store, State st, String world) {
        ComponentType<EntityStore, ObjectiveHistoryComponent> type = historyType();
        if (type == null) {
            return;
        }
        try {
            ObjectiveHistoryComponent history = store.getComponent(ref, type);
            Map<String, ?> map = history == null ? null : history.getObjectiveHistoryMap();
            if (map == null) {
                return;
            }
            Set<String> before = st.objectives;
            if (before != null && before.size() == map.size() && before.containsAll(map.keySet())) {
                return;
            }
            Set<String> now = new HashSet<>(map.keySet());
            now.remove(null);
            st.objectives = now;
            for (String id : now) {
                if (isNew(uuid, before, Keys.P_OBJECTIVE, id) && s.publisher.wants(EventKind.OBJECTIVE_COMPLETED)) {
                    s.publisher.publish(new ObjectiveCompleted(s.header(world, uuid), id));
                }
            }
        } catch (LinkageError e) {
            objectivesOff = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read objectives on this server, OBJECTIVE_COMPLETED is off");
        }
    }

    /** The objective history type has no static accessor and comes from the objectives plugin. */
    private ComponentType<EntityStore, ObjectiveHistoryComponent> historyType() {
        ComponentType<EntityStore, ObjectiveHistoryComponent> type = historyType;
        if (type != null || objectivesOff) {
            return type;
        }
        try {
            ObjectivePlugin plugin = ObjectivePlugin.get();
            type = plugin == null ? null : plugin.getObjectiveHistoryComponentType();
        } catch (RuntimeException e) {
            return null; // the plugin is not up yet, try again next round
        } catch (LinkageError e) {
            objectivesOff = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read objectives on this server, OBJECTIVE_COMPLETED is off");
            return null;
        }
        historyType = type;
        return type;
    }

    private void configData(UUID uuid, Player player, State st, String world) {
        PlayerConfigData data;
        try {
            data = player.getPlayerConfigData();
        } catch (LinkageError e) {
            recipesOff = true;
            reputationOff = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read player data on this server, recipes and reputation are off");
            return;
        }
        if (data == null) {
            return;
        }
        if (!recipesOff) {
            try {
                recipes(uuid, data.getKnownRecipes(), st, world);
            } catch (LinkageError e) {
                recipesOff = true;
                logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read known recipes on this server, RECIPE_LEARNED is off");
            }
        }
        if (!reputationOff) {
            try {
                reputation(uuid, data.getReputationData(), st, world);
            } catch (LinkageError e) {
                reputationOff = true;
                logger.at(Level.WARNING).withCause(e).log("TrackerAPI cannot read reputation on this server, REPUTATION_CHANGED is off");
            }
        }
    }

    private void recipes(UUID uuid, Set<String> known, State st, String world) {
        if (known == null) {
            return;
        }
        Set<String> before = st.recipes;
        if (before != null && before.size() == known.size() && before.containsAll(known)) {
            return;
        }
        Set<String> now = new HashSet<>(known);
        now.remove(null);
        st.recipes = now;
        for (String id : now) {
            if (isNew(uuid, before, Keys.P_RECIPE, id) && s.publisher.wants(EventKind.RECIPE_LEARNED)) {
                s.publisher.publish(new RecipeLearned(s.header(world, uuid), id));
            }
        }
    }

    private void reputation(UUID uuid, Map<String, Integer> data, State st, String world) {
        if (data == null) {
            return;
        }
        Map<String, Integer> before = st.reputation;
        if (before != null && before.equals(data)) {
            return;
        }
        Map<String, Integer> now = new HashMap<>(Math.max(4, data.size() * 2));
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                now.put(e.getKey(), e.getValue());
            }
        }
        st.reputation = now;
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int to = e.getValue();
            int from;
            if (before != null) {
                Integer was = before.get(e.getKey());
                from = was == null ? 0 : was;
            } else {
                from = (int) s.counters.get(uuid, Keys.of(Keys.P_REPUTATION, e.getKey()));
            }
            if (from != to && s.publisher.wants(EventKind.REPUTATION_CHANGED)) {
                s.publisher.publish(new ReputationChanged(s.header(world, uuid), e.getKey(), from, to));
            }
        }
    }
}
