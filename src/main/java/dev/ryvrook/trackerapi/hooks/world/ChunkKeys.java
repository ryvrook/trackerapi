package dev.ryvrook.trackerapi.hooks.world;

/**
 * Chunk coordinate math, the same packing as the engine's {@code ChunkUtil}. A chunk index holds the chunk
 * x in the high 32 bits and the chunk z in the low 32 bits, a chunk is 32 blocks wide. Pure, no engine types,
 * so the tests check it against {@code ChunkUtil} without a server.
 */
public final class ChunkKeys {
    /** Blocks per chunk side as a shift, 32 blocks. */
    public static final int BITS = 5;

    private ChunkKeys() {
    }

    /** The packed index of chunk x z. */
    public static long index(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** The chunk x of a packed index. */
    public static int x(long index) {
        return (int) (index >> 32);
    }

    /** The chunk z of a packed index. */
    public static int z(long index) {
        return (int) index;
    }

    /** The chunk coordinate a block coordinate falls in, rounding toward negative infinity. */
    public static int ofBlock(int block) {
        return block >> BITS;
    }
}
