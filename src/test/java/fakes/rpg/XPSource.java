package fakes.rpg;

import java.util.Objects;

/** The source of an XP gain, a final class with named constants and a factory, equal by name. */
public final class XPSource {
    public static final XPSource ENTITY_KILL = new XPSource("ENTITY_KILL", null);
    public static final XPSource COMMAND = new XPSource("COMMAND", null);
    public static final XPSource HARVEST = new XPSource("HARVEST", null);
    public static final XPSource BLOCK_BREAK = new XPSource("BLOCK_BREAK", null);
    public static final XPSource TAME = new XPSource("TAME", null);

    private final String name;
    private final Object metadata;

    private XPSource(String name, Object metadata) {
        this.name = name;
        this.metadata = metadata;
    }

    public static XPSource create(String name) {
        return new XPSource(name, null);
    }

    public static XPSource create(String name, Object metadata) {
        return new XPSource(name, metadata);
    }

    public String getName() {
        return name;
    }

    public Object getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof XPSource s && Objects.equals(name, s.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
