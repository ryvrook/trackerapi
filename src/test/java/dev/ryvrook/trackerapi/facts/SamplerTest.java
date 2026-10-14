package dev.ryvrook.trackerapi.facts;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The parts of the sampler that run without an engine, the minute duties and the leave cleanup. */
class SamplerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @TempDir
    Path dir;

    @Test
    void everyMinuteRaisesTheLongestSessionOfEveryOnlinePlayer() {
        Services s = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        Sampler sampler = new Sampler(s, TestSupport.LOGGER);
        PlayerRegistry.Online o = s.players.join(A, "Alder", 1_000L);
        o.sessionStart = 1_000L;
        s.counters.join(A, "Alder", 1_000L);

        sampler.minute(1_000L + 90_000L);
        assertEquals(90, s.counters.get(A, Keys.SESSION_LONGEST), "a session still running counts before it ends");
        sampler.minute(1_000L + 150_000L);
        assertEquals(150, s.counters.get(A, Keys.SESSION_LONGEST));
    }

    @Test
    void aLeaveDropsTheSessionAtOnceAndTheAntiFarmMemoryAfterTheGrace() {
        Services s = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        Sampler sampler = new Sampler(s, TestSupport.LOGGER);
        int[] forgotten = {0};
        s.forgetting.onPlayer(u -> forgotten[0]++);
        sampler.left(A, 10_000L);
        assertEquals(0, sampler.stateCount());
        assertEquals(1, sampler.offlineCount());
        sampler.sweepOffline(10_000L + Sampler.OFFLINE_KEEP_MILLIS);
        assertEquals(0, forgotten[0], "still within the hour");
        sampler.sweepOffline(10_001L + Sampler.OFFLINE_KEEP_MILLIS);
        assertEquals(1, forgotten[0]);
        assertEquals(0, sampler.offlineCount());
    }
}
