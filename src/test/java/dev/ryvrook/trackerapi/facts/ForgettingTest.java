package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The leave list runs at once and apart from the hour of grace list, the sweep list on its own. */
class ForgettingTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void leaveAndForgetRunTheirOwnLists() {
        Forgetting f = new Forgetting();
        List<String> seen = new ArrayList<>();
        f.onLeave(u -> seen.add("leave"));
        f.onPlayer(u -> seen.add("forget"));
        f.onSweep(() -> seen.add("sweep"));
        f.onLeave(u -> {
            throw new IllegalStateException("one failure");
        });
        f.onLeave(u -> seen.add("leave2"));
        f.leave(A);
        assertEquals(List.of("leave", "leave2"), seen, "a failing callback does not stop the others");
        f.forget(A);
        f.sweep();
        assertEquals(List.of("leave", "leave2", "forget", "sweep"), seen);
        f.leave(null);
        assertEquals(4, seen.size());
    }
}
