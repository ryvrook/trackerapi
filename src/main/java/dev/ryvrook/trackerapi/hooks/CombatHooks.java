package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.facts.BossLedger;
import dev.ryvrook.trackerapi.facts.TameWatch;
import dev.ryvrook.trackerapi.hooks.combat.CreatureLifeSystem;
import dev.ryvrook.trackerapi.hooks.combat.DamageDealtSystem;
import dev.ryvrook.trackerapi.hooks.combat.EffectSystem;
import dev.ryvrook.trackerapi.hooks.combat.EntityDiedSystem;
import dev.ryvrook.trackerapi.hooks.combat.ProjectileLandSystem;
import dev.ryvrook.trackerapi.hooks.combat.ProjectileLaunchSystem;
import dev.ryvrook.trackerapi.hooks.combat.TameSystem;
import dev.ryvrook.trackerapi.hooks.combat.VitalsSystem;

import java.util.logging.Level;

/**
 * The combat hook set. Damage, deaths with boss credit, creatures spawning, leaving and being tamed,
 * projectiles, status effects and the optional vitals sample. Each engine system is its own class under
 * {@code hooks.combat} and is registered in its own guard. The boss ledger and the tame watch are held here,
 * one of each per plugin instance.
 */
public final class CombatHooks implements HookSet {
    private final BossLedger ledger = new BossLedger();
    private final TameWatch tames = new TameWatch();
    private EffectSystem effects;

    @Override
    public String name() {
        return "combat";
    }

    @Override
    public void install(PluginBase plugin, Services s, Registrar registrar) {
        // After the engine's damage filters when the engine lets us, so the amount is the final one.
        registrar.entitySystem("combat.damage", () -> {
            try {
                return new DamageDealtSystem(s, ledger, true);
            } catch (RuntimeException | LinkageError e) {
                unordered(s);
                return new DamageDealtSystem(s, ledger, false);
            }
        });
        if (registrar.failed().contains("combat.damage")) {
            unordered(s);
            registrar.entitySystem("combat.damage.unordered", () -> new DamageDealtSystem(s, ledger, false));
        }
        registrar.entitySystem("combat.death", () -> new EntityDiedSystem(s, ledger));
        registrar.entitySystem("combat.creatures", () -> new CreatureLifeSystem(s));
        registrar.entitySystem("combat.tame", () -> new TameSystem(s, tames));
        registrar.entitySystem("combat.projectile.launch", () -> new ProjectileLaunchSystem(s));
        registrar.entitySystem("combat.projectile.land", () -> new ProjectileLandSystem(s));
        registrar.entitySystem("combat.effects", () -> {
            EffectSystem system = new EffectSystem(s);
            effects = system;
            return system;
        });
        int vitals = s.config().vitalsSeconds;
        if (vitals > 0) {
            registrar.entitySystem("combat.vitals", () -> new VitalsSystem(s, vitals));
        }
        s.forgetting.onPlayer(uuid -> {
            EffectSystem e = effects;
            if (e != null) {
                e.forget(uuid);
            }
        });
        s.forgetting.onWorld(ledger::forgetWorld);
    }

    private static void unordered(Services s) {
        s.logger.at(Level.INFO).log("TrackerAPI could not order its damage hook after the engine's damage filters, "
                + "so damage amounts may be read before armour");
    }

    @Override
    public void stop() {
        ledger.clear();
        tames.clear();
        EffectSystem e = effects;
        if (e != null) {
            e.clear();
        }
    }
}
