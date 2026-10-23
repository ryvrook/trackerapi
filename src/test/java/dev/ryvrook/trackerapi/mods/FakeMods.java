package dev.ryvrook.trackerapi.mods;

import java.io.IOException;
import java.io.InputStream;

/**
 * Test support for the bindings. {@link #loader} gives a class loader that defines every class under the
 * {@code fakes.} packages anew from the test classpath bytes, so a fake mod API class loaded through it has a
 * different {@code Class} than the one the test code names, exactly like a live mod's classes live in the mod's
 * own loader and not in TrackerAPI's. A binding under test must resolve everything through that loader with
 * {@link Handles} and never touch the fake types directly.
 */
public final class FakeMods {
    private FakeMods() {
    }

    /** A fresh isolating loader. Two calls give two loaders with distinct classes, as two reloads of a mod would. */
    public static ClassLoader loader() {
        return new IsolatingLoader(FakeMods.class.getClassLoader());
    }

    /** A plain object standing in for a plugin instance, its class loader is the isolating loader. */
    public static Object plugin(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName("fakes.demo.DemoPlugin", true, loader);
        return type.getConstructor().newInstance();
    }

    static final class IsolatingLoader extends ClassLoader {
        IsolatingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("fakes.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    byte[] bytes = bytes(name);
                    c = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        private byte[] bytes(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(path)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
