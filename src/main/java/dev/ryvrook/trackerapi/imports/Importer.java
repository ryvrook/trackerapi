package dev.ryvrook.trackerapi.imports;

import dev.ryvrook.trackerapi.Services;

/** Placeholder until the imports package is written. See docs/CONTRACT.md section 8. */
public final class Importer {
    private final Services services;

    public Importer(Services services) {
        this.services = services;
    }

    /** IO thread. Runs the Feats and Chronicle import once, when no import state file exists yet. */
    public void runFirstBootIfNeeded() {
    }
}
