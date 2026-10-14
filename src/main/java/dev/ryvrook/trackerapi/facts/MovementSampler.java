package dev.ryvrook.trackerapi.facts;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the once a second position samples of each player into whole metres travelled. A step shorter
 * than {@link #MIN_STEP} is jitter and one longer than {@link #MAX_STEP} is a teleport or a world change,
 * neither counts. The fraction of a metre left over carries into the next sample, so slow walking adds
 * up. Steps taken while swimming, gliding or riding also feed that mode's own share, with its own carried
 * fraction. Riding is sampled from the mount's own position with a second instance, see {@link Sampler}.
 * Thread safe.
 */
public final class MovementSampler {
    /** Shorter steps are standing still. */
    public static final double MIN_STEP = 0.25;
    /** Longer steps in one second are teleports, portals and world changes. */
    public static final double MAX_STEP = 64.0;

    /** How the player moved during a step. WALK covers everything that is not one of the others. */
    public enum Mode { WALK, SWIM, GLIDE, RIDE }

    /**
     * Whole metres earned by one sample. {@code modeMetres} belong to {@code mode} and are zero for WALK.
     * {@code moved} is true for every step inside the bounds, even one that earned no whole metre yet.
     */
    public record Step(long metres, Mode mode, long modeMetres, boolean moved) {
        public static final Step NONE = new Step(0, Mode.WALK, 0, false);
        private static final Step[] SMALL = small();

        private static Step[] small() {
            Mode[] modes = Mode.values();
            Step[] out = new Step[modes.length];
            for (Mode m : modes) {
                out[m.ordinal()] = new Step(0, m, 0, true);
            }
            return out;
        }

        /** A step inside the bounds that earned no whole metre. Shared, no allocation. */
        public static Step small(Mode mode) {
            return SMALL[(mode == null ? Mode.WALK : mode).ordinal()];
        }
    }

    private static final class Track {
        double x;
        double y;
        double z;
        boolean placed;
        double remainder;
        final double[] modeRemainder = new double[Mode.values().length];
    }

    private final Map<UUID, Track> tracks = new ConcurrentHashMap<>();

    /**
     * Records a position and returns the whole metres it adds. The first sample after a join or a world
     * change only sets the starting point.
     */
    public Step sample(UUID player, double x, double y, double z, Mode mode) {
        if (player == null) {
            return Step.NONE;
        }
        Track track = tracks.computeIfAbsent(player, id -> new Track());
        synchronized (track) {
            boolean had = track.placed;
            double dx = x - track.x;
            double dy = y - track.y;
            double dz = z - track.z;
            track.x = x;
            track.y = y;
            track.z = z;
            track.placed = true;
            if (!had) {
                return Step.NONE;
            }
            double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (step < MIN_STEP || step > MAX_STEP || Double.isNaN(step)) {
                return Step.NONE;
            }
            long metres = carry(track, -1, step);
            long modeMetres = 0;
            if (mode != null && mode != Mode.WALK) {
                modeMetres = carry(track, mode.ordinal(), step);
            }
            if (metres == 0 && modeMetres == 0) {
                return Step.small(mode);
            }
            return new Step(metres, mode == null ? Mode.WALK : mode, modeMetres, true);
        }
    }

    /** Adds a step to a carried fraction and takes the whole metres off it. Slot -1 is the total. */
    private static long carry(Track track, int slot, double step) {
        double total = (slot < 0 ? track.remainder : track.modeRemainder[slot]) + step;
        long whole = (long) Math.floor(total);
        if (whole < 1) {
            whole = 0;
        }
        double left = total - whole;
        if (slot < 0) {
            track.remainder = left;
        } else {
            track.modeRemainder[slot] = left;
        }
        return whole;
    }

    /** Forgets the last position, so the next sample starts afresh. The carried fractions stay. */
    public void resetPosition(UUID player) {
        Track track = player == null ? null : tracks.get(player);
        if (track != null) {
            synchronized (track) {
                track.placed = false;
            }
        }
    }

    /** Forgets everything about the player. */
    public void forget(UUID player) {
        if (player != null) {
            tracks.remove(player);
        }
    }

    /** How many players are tracked, for tests and the status line. */
    public int size() {
        return tracks.size();
    }
}
