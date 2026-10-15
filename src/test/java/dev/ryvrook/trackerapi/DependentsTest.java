package dev.ryvrook.trackerapi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DependentsTest {
    private static final String COINAGE = "ryvrook:coinage";
    private static final String CHRONICLE = "ryvrook:chroniclestats";
    private static final String DOMINION = "ryvrook:dominion";
    private static final String FEATS = "ryvrook:feats";
    private static final String ATLAS = "ryvrook:atlas";
    private static final String TRACKER = "ryvrook:trackerapi";

    /** The optional dependencies of the suite as their manifests list them. */
    private static final Map<String, Set<String>> SUITE = Map.of(
            COINAGE, Set.of(TRACKER, "theneweconomy:vaultunlocked"),
            CHRONICLE, Set.of(TRACKER, COINAGE),
            DOMINION, Set.of(TRACKER, FEATS, ATLAS, COINAGE),
            FEATS, Set.of(TRACKER),
            ATLAS, Set.of(TRACKER));

    @Test
    void aPluginIsReloadedAfterTheDependentsItReads() {
        // Coinage was reloaded by hand earlier, so it now sits last in the plugin map.
        List<String> mapOrder = List.of(DOMINION, CHRONICLE, FEATS, ATLAS, COINAGE);
        assertEquals(List.of(FEATS, ATLAS, COINAGE, DOMINION, CHRONICLE), Dependents.order(mapOrder, SUITE));
    }

    @Test
    void theLoadOrderStaysWhenNothingDependsOnAnother() {
        List<String> keys = List.of(FEATS, ATLAS);
        assertEquals(keys, Dependents.order(keys, SUITE));
    }

    @Test
    void aCycleStillReloadsEveryPluginOnce() {
        Map<String, Set<String>> cycle = Map.of("a:a", Set.of("b:b"), "b:b", Set.of("a:a"));
        assertEquals(List.of("b:b", "a:a"), Dependents.order(List.of("a:a", "b:b"), cycle));
    }
}
