package fakes.arcanum.api.spell;

import fakes.arcanum.api.knowledge.SpellKnowledge;
import fakes.arcanum.internal.ArcanumApiProvider;

/** The public API of the fake mod, the static accessor and the knowledge service as the real one has them. */
public interface ArcanumApi {
    static ArcanumApi get() {
        return ArcanumApiProvider.get();
    }

    SpellKnowledge knowledge();
}
