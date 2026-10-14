package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.MovementEdge;
import dev.ryvrook.trackerapi.facts.MoveBits;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * MOVEMENT_EDGE, the only per tick work of the players hook set. Jumps, falls, far falls, landings, glides,
 * swims, sprints, crouches and climbs come from edges of the movement states. The steady state of a player who
 * does not change state is one map lookup, one component read and an int compare, with no allocation.
 *
 * <p>Threads. One edge map per world, created on that world's thread and read and written only by that
 * thread. The outer map is concurrent so other threads can find a world's entry. Invalid refs are swept on
 * the world thread about every 600 ticks per player. {@code isParallel} is not overridden, so one world
 * thread runs all of a world's ticks.
 */
final class MovementEdgeSystem extends EntityTickingSystem<EntityStore> {
    private static final int SWEEP_TICKS = 600;

    private final ConcurrentHashMap<World, WorldEdges> edges = new ConcurrentHashMap<>();
    private final Services s;
    /** Set on the first LinkageError, so a missing engine class costs one warning rather than one per tick. */
    private volatile boolean disabled;

    MovementEdgeSystem(Services services) {
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> commandBuffer) {
        if (disabled || !s.publisher.wants(EventKind.MOVEMENT_EDGE)) {
            return;
        }
        long start = System.nanoTime();
        try {
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            WorldEdges we = edges.get(world);
            if (we == null || we.world != world) {
                if (!WorldTasks.isAlive(world)) {
                    return; // a world being removed gets no entry that would outlive its removal
                }
                // A world recreated under the same name gets a fresh map here.
                we = new WorldEdges(world);
                edges.put(world, we);
            }
            MovementStatesComponent msc = chunk.getComponent(index, MovementStatesComponent.getComponentType());
            MovementStates states = msc == null ? null : msc.getMovementStates();
            if (states == null) {
                return;
            }
            int bits = pack(states);
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            EdgeState e = we.map.get(ref);
            if (e == null) {
                PlayerRef pr = chunk.getComponent(index, PlayerRef.getComponentType());
                if (pr != null) {
                    we.map.put(ref, new EdgeState(pr.getUuid(), bits));
                }
            } else if (e.bits != bits) {
                int before = e.bits;
                e.bits = bits;
                int mask = MoveBits.edges(before, bits);
                if (mask != 0) {
                    publish(e, mask, chunk, index, world.getName());
                }
            }
            if (++we.calls >= SWEEP_TICKS * Math.max(1, we.map.size())) {
                we.calls = 0;
                we.map.keySet().removeIf(r -> r == null || !r.isValid());
            }
        } catch (LinkageError ex) {
            disabled = true;
            s.logger.at(Level.WARNING).withCause(ex).log("TrackerAPI movement edges are not supported on this server and have been turned off");
        } catch (RuntimeException ex) {
            Guard.failed(ex);
        } finally {
            s.perf.record(EventKind.MOVEMENT_EDGE, System.nanoTime() - start);
        }
    }

    private void publish(EdgeState e, int mask, ArchetypeChunk<EntityStore> chunk, int index, String world) {
        double y = y(chunk, index);
        for (int m = mask, i; m != 0; m &= m - 1) {
            i = Integer.numberOfTrailingZeros(m);
            MovementEdge.Edge edge = MoveBits.edge(i);
            double fall = 0;
            if (edge == MovementEdge.Edge.LANDED) {
                fall = landed(e, chunk, index, y);
            } else if (edge == MovementEdge.Edge.FALL_START) {
                e.fallStartY = y;
            }
            s.publisher.publish(new MovementEdge(s.header(world, e.uuid), edge, Double.isNaN(y) ? 0 : y, fall));
        }
    }

    /** Blocks fallen, from the height the fall started at, else the engine's own fall distance. */
    private static double landed(EdgeState e, ArchetypeChunk<EntityStore> chunk, int index, double y) {
        double start = e.fallStartY;
        e.fallStartY = Double.NaN;
        if (!Double.isNaN(start) && !Double.isNaN(y)) {
            return Math.max(0, start - y);
        }
        try {
            Player player = chunk.getComponent(index, Player.getComponentType());
            double d = player == null ? 0 : player.getCurrentFallDistance();
            return Double.isNaN(d) || d < 0 ? 0 : d;
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    static int pack(MovementStates st) {
        return MoveBits.pack(st.idle, st.jumping, st.flying, st.walking, st.running, st.sprinting, st.crouching,
                st.falling, st.fallingFar, st.climbing, st.inFluid, st.swimming, st.swimJumping, st.onGround,
                st.mantling, st.sliding, st.mounting, st.rolling, st.sitting, st.gliding, st.sleeping);
    }

    private static double y(ArchetypeChunk<EntityStore> chunk, int index) {
        TransformComponent tc = chunk.getComponent(index, TransformComponent.getComponentType());
        return tc == null || tc.getPosition() == null ? Double.NaN : tc.getPosition().y;
    }

    /** A teleport, so a fall in progress does not credit the jump. Call it on the world's own thread. */
    void teleported(World world, Ref<EntityStore> ref) {
        WorldEdges we = world == null ? null : edges.get(world);
        if (we != null && we.world == world) {
            EdgeState e = we.map.get(ref);
            if (e != null) {
                e.fallStartY = Double.NaN;
            }
        }
    }

    /** After the world was removed, when its thread no longer ticks. */
    void forgetWorld(String name) {
        if (name != null) {
            edges.keySet().removeIf(w -> name.equals(w.getName()));
        }
    }

    /** Timer thread, once a minute. Drops the maps of worlds the universe no longer holds. */
    void sweep() {
        edges.keySet().removeIf(WorldTasks::isGone);
    }

    void clear() {
        edges.clear();
    }

    /** The edge map of one world and its sweep counter. Touched only by that world's thread. */
    private static final class WorldEdges {
        final World world;
        final IdentityHashMap<Ref<EntityStore>, EdgeState> map = new IdentityHashMap<>();
        int calls;

        WorldEdges(World world) {
            this.world = world;
        }
    }

    /** The last bits of one player, the uuid read once when the ref was first seen, and a fall in progress. */
    private static final class EdgeState {
        final UUID uuid;
        int bits;
        double fallStartY = Double.NaN;

        EdgeState(UUID uuid, int bits) {
            this.uuid = uuid;
            this.bits = bits;
        }
    }
}
