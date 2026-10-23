package fakes.arcanum.internal;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import fakes.arcanum.api.knowledge.SpellKnowledge;
import fakes.arcanum.api.spell.ArcanumApi;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The static holder of the fake API. The knowledge keeps the learned pieces per entity ref. */
public final class ArcanumApiProvider {
    private static final Set<String> STARTERS = Set.of("arcanum:damage", "arcanum:heal", "arcanum:connector");
    private static final Map<Ref<?>, Set<String>> learned = new ConcurrentHashMap<>();
    private static volatile boolean failing;

    private static final SpellKnowledge KNOWLEDGE = new SpellKnowledge() {
        @Override
        public boolean isLearned(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor, String pieceId) {
            return learnedPieces(ref, accessor).contains(pieceId);
        }

        @Override
        public Set<String> learnedPieces(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
            if (failing) {
                throw new IllegalStateException("knowledge broke");
            }
            if (ref == null) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>(STARTERS);
            out.addAll(learned.getOrDefault(ref, Set.of()));
            return out;
        }
    };

    private static final ArcanumApi API = () -> KNOWLEDGE;

    private ArcanumApiProvider() {
    }

    public static ArcanumApi get() {
        return API;
    }

    // ---- test knobs, not in the real mod ----

    public static void learn(Ref<?> ref, String pieceId) {
        learned.computeIfAbsent(ref, r -> ConcurrentHashMap.newKeySet()).add(pieceId);
    }

    public static void setFailing(boolean on) {
        failing = on;
    }
}
