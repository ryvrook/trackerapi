package fakes.arcanum.spell.compile;

import java.util.Map;

/** The fake compiled spell, the record accessors the binding reads. */
public record CompiledSpell(String name, Map<Object, CompiledNode> nodes, CompiledSpellStats stats) {
}
