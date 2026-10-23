package dev.ryvrook.trackerapi.mods;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The by name lookups: a unique match resolves, an overload set needs the expected parameter names or throws. */
class HandlesTest {
    /** A mod type with overloads the binding does not want to name, and a bridge method from the generic interface. */
    public static final class Overloaded implements Supplier<UUID> {
        public String read(String s) {
            return "string " + s;
        }

        public String read(Integer i) {
            return "integer " + i;
        }

        public String only(String s) {
            return "only " + s;
        }

        @Override
        public UUID get() {
            return UUID.fromString("00000000-0000-0000-0000-000000000007");
        }

        public static String make(String s) {
            return "make string";
        }

        public static String make(Long l) {
            return "make long";
        }
    }

    @Test
    void uniqueMethodResolvesWithoutExpectedNames() throws Throwable {
        MethodHandle only = Handles.virtualByName(Overloaded.class, "only", 1);
        assertEquals("only x", only.invoke(new Overloaded(), "x"));
    }

    @Test
    void bridgeMethodsDoNotMakeAZeroArgLookupAmbiguous() throws Throwable {
        MethodHandle get = Handles.virtualByName(Overloaded.class, "get", 0);
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000007"), get.invoke(new Overloaded()));
    }

    @Test
    void overloadsNeedTheExpectedParameterNames() throws Throwable {
        NoSuchMethodException e = assertThrows(NoSuchMethodException.class, () -> Handles.virtualByName(Overloaded.class, "read", 1));
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
        MethodHandle asString = Handles.virtualByName(Overloaded.class, "read", 1, "String");
        MethodHandle asInteger = Handles.virtualByName(Overloaded.class, "read", 1, "Integer");
        assertEquals("string a", asString.invoke(new Overloaded(), "a"));
        assertEquals("integer 3", asInteger.invoke(new Overloaded(), 3));
        assertThrows(NoSuchMethodException.class, () -> Handles.virtualByName(Overloaded.class, "read", 1, "Double"),
                "an expected name nothing matches is still ambiguous");
        assertThrows(NoSuchMethodException.class, () -> Handles.virtualByName(Overloaded.class, "missing", 1));
    }

    @Test
    void staticOverloadsFollowTheSameRule() throws Throwable {
        assertThrows(NoSuchMethodException.class, () -> Handles.statikByName(Overloaded.class, "make", 1));
        assertEquals("make long", Handles.statikByName(Overloaded.class, "make", 1, "Long").invoke(4L));
        assertThrows(NoSuchMethodException.class, () -> Handles.statikByName(Overloaded.class, "read", 1, "String"),
                "an instance method is not a static one");
    }
}
