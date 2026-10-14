package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.math.util.ChunkUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkKeysTest {
    private static final int[] COORDS = {0, 1, -1, 31, -32, 1_000, -1_000, 65_535, -65_536, Integer.MAX_VALUE, Integer.MIN_VALUE};

    @Test
    void packsAsTheEngineDoes() {
        for (int x : COORDS) {
            for (int z : COORDS) {
                assertEquals(ChunkUtil.indexChunk(x, z), ChunkKeys.index(x, z), "index of " + x + " " + z);
            }
        }
    }

    @Test
    void unpacksBothHalves() {
        for (int x : COORDS) {
            for (int z : COORDS) {
                long index = ChunkKeys.index(x, z);
                assertEquals(x, ChunkKeys.x(index));
                assertEquals(z, ChunkKeys.z(index));
                assertEquals(ChunkUtil.xOfChunkIndex(index), ChunkKeys.x(index));
                assertEquals(ChunkUtil.zOfChunkIndex(index), ChunkKeys.z(index));
            }
        }
    }

    @Test
    void blockToChunkRoundsDown() {
        assertEquals(0, ChunkKeys.ofBlock(0));
        assertEquals(0, ChunkKeys.ofBlock(31));
        assertEquals(1, ChunkKeys.ofBlock(32));
        assertEquals(-1, ChunkKeys.ofBlock(-1));
        assertEquals(-1, ChunkKeys.ofBlock(-32));
        assertEquals(-2, ChunkKeys.ofBlock(-33));
        for (int b = -200; b <= 200; b++) {
            assertEquals(ChunkUtil.chunkCoordinate(b), ChunkKeys.ofBlock(b), "block " + b);
        }
    }
}
