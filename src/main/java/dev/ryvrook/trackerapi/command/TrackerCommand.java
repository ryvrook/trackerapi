package dev.ryvrook.trackerapi.command;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import dev.ryvrook.trackerapi.TrackerApiPlugin;

/** Placeholder until the command package is written. See docs/CONTRACT.md section 9. */
public final class TrackerCommand extends CommandBase {
    private final TrackerApiPlugin plugin;

    public TrackerCommand(TrackerApiPlugin plugin) {
        super("trackerapi", "TrackerAPI status and admin commands");
        this.plugin = plugin;
        requirePermission("trackerapi.admin");
    }

    @Override
    protected void executeSync(CommandContext context) {
        context.sendMessage(com.hypixel.hytale.server.core.Message.raw("TrackerAPI " + plugin.version() + " is running"));
    }
}
