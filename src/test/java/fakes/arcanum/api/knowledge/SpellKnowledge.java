package fakes.arcanum.api.knowledge;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;

/** The knowledge reads of the fake mod with the real signatures. */
public interface SpellKnowledge {
    boolean isLearned(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor, String pieceId);

    Set<String> learnedPieces(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor);
}
