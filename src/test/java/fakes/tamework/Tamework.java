package fakes.tamework;

import fakes.tamework.api.TameworkApi;

/**
 * The plugin object of the fake mod, shaped like the real main class: a static instance set at construction and a
 * volatile API that is null until {@link #install()} and null again after {@link #uninstall()}, as the real one is
 * null until its async setup ran and after shutdown.
 */
public final class Tamework {
    private static Tamework instance;
    private volatile FakeApi api;

    public Tamework() {
        instance = this;
    }

    public static Tamework getInstance() {
        return instance;
    }

    public TameworkApi getApi() {
        return api;
    }

    /** Builds the API, as the setup step does, and returns it so a test can drive it. */
    public FakeApi install() {
        FakeApi a = new FakeApi();
        api = a;
        return a;
    }

    public void uninstall() {
        api = null;
    }
}
