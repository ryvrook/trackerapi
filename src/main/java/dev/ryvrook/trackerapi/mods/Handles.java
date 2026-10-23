package dev.ryvrook.trackerapi.mods;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

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
     * types the binding does not want to name. The first public method with that name and count wins.
     */
    public static MethodHandle virtualByName(Class<?> owner, String name, int params) throws ReflectiveOperationException {
        Method found = null;
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params && !Modifier.isStatic(m.getModifiers())) {
                found = m;
                break;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name + "/" + params);
        }
        MethodHandle mh = MethodHandles.publicLookup().unreflect(found);
        return mh.asType(mh.type().generic());
    }

    /** A public static method found by name and parameter count. */
    public static MethodHandle statikByName(Class<?> owner, String name, int params) throws ReflectiveOperationException {
        Method found = null;
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params && Modifier.isStatic(m.getModifiers())) {
                found = m;
                break;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name + "/" + params);
        }
        MethodHandle mh = MethodHandles.publicLookup().unreflect(found);
        return mh.asType(mh.type().generic());
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
