package dev.ryvrook.trackerapi.mods;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection helpers for the mod bindings. Every class is loaded through the mod's class loader, every handle is
 * adapted to an all Object signature so a binding never names a mod type, and a proxy for a listener interface
 * is made in the mod's loader so the mod can call it.
 */
public final class Handles {
    private Handles() {
    }

    /** The class by name through the mod's loader. */
    public static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /**
     * A public instance method as a handle with an all Object signature, {@code (Object receiver, Object... args) -> Object}.
     * Primitive parameters and returns are boxed by the adaptation.
     */
    public static MethodHandle virtual(Class<?> owner, String name, Class<?> returns, Class<?>... params)
            throws ReflectiveOperationException {
        MethodHandle mh = MethodHandles.publicLookup().findVirtual(owner, name, MethodType.methodType(returns, params));
        return mh.asType(mh.type().generic());
    }

    /** A public static method as a handle with an all Object signature. */
    public static MethodHandle statik(Class<?> owner, String name, Class<?> returns, Class<?>... params)
            throws ReflectiveOperationException {
        MethodHandle mh = MethodHandles.publicLookup().findStatic(owner, name, MethodType.methodType(returns, params));
        return mh.asType(mh.type().generic());
    }

    /**
     * A public instance method found by name and parameter count, for a method whose parameter types are mod
     * types the binding does not want to name. Bridge and synthetic methods are skipped. When several public
     * methods share the name and count, the one whose parameter simple names equal {@code expected} wins, and
     * without a single match the lookup throws rather than guess.
     */
    public static MethodHandle virtualByName(Class<?> owner, String name, int params, String... expected) throws ReflectiveOperationException {
        return byName(owner, name, params, false, expected);
    }

    /** A public static method found by name and parameter count, with the same tie break as {@link #virtualByName}. */
    public static MethodHandle statikByName(Class<?> owner, String name, int params, String... expected) throws ReflectiveOperationException {
        return byName(owner, name, params, true, expected);
    }

    private static MethodHandle byName(Class<?> owner, String name, int params, boolean wantStatic, String[] expected)
            throws ReflectiveOperationException {
        List<Method> found = new ArrayList<>(2);
        for (Method m : owner.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != params || Modifier.isStatic(m.getModifiers()) != wantStatic
                    || m.isBridge() || m.isSynthetic()) {
                continue;
            }
            if (!hasSameParameters(found, m)) {
                found.add(m);
            }
        }
        String what = owner.getName() + "." + name + "/" + params;
        if (found.isEmpty()) {
            throw new NoSuchMethodException(what);
        }
        Method one = found.get(0);
        if (found.size() > 1) {
            one = null;
            if (expected != null && expected.length == params) {
                for (Method m : found) {
                    if (parametersNamed(m, expected)) {
                        if (one != null) {
                            one = null;
                            break;
                        }
                        one = m;
                    }
                }
            }
            if (one == null) {
                throw new NoSuchMethodException(what + " is ambiguous, " + found.size() + " public methods match");
            }
        }
        MethodHandle mh = MethodHandles.publicLookup().unreflect(one);
        return mh.asType(mh.type().generic());
    }

    /** True when a method with the same parameter types is already in the list, an interface redeclaration. */
    private static boolean hasSameParameters(List<Method> list, Method m) {
        for (Method other : list) {
            if (java.util.Arrays.equals(other.getParameterTypes(), m.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    private static boolean parametersNamed(Method m, String[] expected) {
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (!types[i].getSimpleName().equals(expected[i])) {
                return false;
            }
        }
        return true;
    }

    /** A public static field's value, for an enum constant or a singleton holder. */
    public static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getField(name);
        if (!Modifier.isStatic(f.getModifiers())) {
            throw new NoSuchFieldException(owner.getName() + "." + name + " is not static");
        }
        return f.get(null);
    }

    /** A public instance method with no parameters, invoked on a mod object, null when it throws. */
    public static Object call(MethodHandle mh, Object receiver) {
        try {
            return mh.invoke(receiver);
        } catch (Throwable t) {
            return null;
        }
    }

    /** An interface implemented by a proxy in the mod's loader. The handler sees the method name and arguments. */
    public static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** The name of an enum constant or the string of any other object, null for null. */
    public static String nameOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Enum<?> e) {
            return e.name();
        }
        return o.toString();
    }

    /** An int from a boxed number, or the fallback. */
    public static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    /** A long from a boxed number, or the fallback. */
    public static long longOf(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    /** A double from a boxed number, or the fallback. */
    public static double doubleOf(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    /** A boolean from a boxed Boolean, or the fallback. */
    public static boolean boolOf(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : fallback;
    }

    /** A String, or null for anything else. */
    public static String stringOf(Object o) {
        return o instanceof String s ? s : null;
    }
}
