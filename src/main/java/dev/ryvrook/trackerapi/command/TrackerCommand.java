package dev.ryvrook.trackerapi.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TrackerApiPlugin;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.imports.Importer;
import dev.ryvrook.trackerapi.journal.EventJson;
import dev.ryvrook.trackerapi.journal.JournalService;
import dev.ryvrook.trackerapi.util.Perf;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * {@code /trackerapi} and its sub commands, for server staff. Every command needs {@code trackerapi.admin},
 * which the console always has. Every command works from the console except {@code lookup here}.
 *
 * <p>Commands never block. Work that reads files runs on the IO thread and the reply goes back through the
 * command context when it completes, on the sender's world thread for players.
 */
public final class TrackerCommand extends AbstractCommandCollection {
    public static final String ADMIN_PERMISSION = "trackerapi.admin";
    static final String TEXT = "#c9d4df";
    static final String ACCENT = "#E8A93B";
    static final String ERROR = "#ff6b6b";
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    static final UUID DEBUG_UUID = UUID.nameUUIDFromBytes("TrackerAPI debug".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final TrackerApiPlugin plugin;
    private final RecentRing ring;
    private final long startedAt = System.currentTimeMillis();

    public TrackerCommand(TrackerApiPlugin plugin) {
        super("trackerapi", "TrackerAPI status and admin commands.");
        requirePermission(ADMIN_PERMISSION);
        this.plugin = plugin;
        Services s = plugin.services();
        this.ring = new RecentRing(k -> s.counterSink.wants(k) || (s.journal() != null && s.journal().wants(k)));
        s.publisher.addSink(ring);
        addSubCommand(new Status(this));
        addSubCommand(new PerfCommand(this));
        addSubCommand(new Recent(this));
        addSubCommand(new Lookup(this));
        addSubCommand(new CountersCommand(this));
        addSubCommand(new Top(this));
        addSubCommand(new Import(this));
        addSubCommand(new Reload(this));
        addSubCommand(new Mods(this));
        addSubCommand(new Debug(this));
    }

    Services s() {
        return plugin.services();
    }

    RecentRing ring() {
        return ring;
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    static Message text(String s) {
        return Message.raw(s).color(TEXT);
    }

    static Message error(String s) {
        return Message.raw(s).color(ERROR);
    }

    /** Sends a reply on the sender's world thread when the sender is a player, directly otherwise. */
    static void reply(CommandContext context, Message message) {
        World world = senderWorld(context);
        if (world == null) {
            context.sendMessage(message);
            return;
        }
        try {
            world.execute(() -> context.sendMessage(message));
        } catch (RuntimeException e) {
            context.sendMessage(message);
        }
    }

    static void replyLines(CommandContext context, List<String> lines) {
        World world = senderWorld(context);
        Runnable send = () -> {
            for (String line : lines) {
                context.sendMessage(text(line));
            }
        };
        if (world == null) {
            send.run();
            return;
        }
        try {
            world.execute(send);
        } catch (RuntimeException e) {
            send.run();
        }
    }

    /** Replies with the lines a future completes with, or one error line when it failed. */
    void replyLater(CommandContext context, CompletableFuture<List<String>> future) {
        future.whenComplete((lines, err) -> {
            if (err != null) {
                s().logger.at(Level.WARNING).withCause(err).log("TrackerAPI command failed");
                reply(context, error("That did not work, see the server log."));
            } else {
                replyLines(context, lines);
            }
        });
    }

    private static World senderWorld(CommandContext context) {
        if (!context.isPlayer()) {
            return null;
        }
        try {
            PlayerRef sender = context.senderAs(PlayerRef.class);
            UUID worldId = sender == null ? null : sender.getWorldUuid();
            return worldId == null ? null : Universe.get().getWorld(worldId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static UUID senderUuid(CommandContext context) {
        if (!context.isPlayer()) {
            return null;
        }
        try {
            PlayerRef sender = context.senderAs(PlayerRef.class);
            return sender == null ? null : sender.getUuid();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A player found by name or uuid. */
    record Target(UUID uuid, String name) {
    }

    /** An online name, then a uuid, then a name from the names index. Completes with null when unknown. */
    CompletableFuture<Target> find(String text) {
        String t = text.trim();
        try {
            UUID u = UUID.fromString(t);
            return CompletableFuture.completedFuture(new Target(u, nameOf(u)));
        } catch (IllegalArgumentException ignored) {
            // not a uuid
        }
        try {
            PlayerRef online = Universe.get().getPlayerByUsername(t, NameMatching.EXACT_IGNORE_CASE);
            if (online != null) {
                return CompletableFuture.completedFuture(new Target(online.getUuid(), online.getUsername()));
            }
        } catch (RuntimeException | LinkageError e) {
            // fall back to the names index
        }
        return s().counters.uuidOf(t).thenApply(u -> u.map(id -> new Target(id, nameOf(id))).orElse(null));
    }

    String nameOf(UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        String online = s().players.nameOf(uuid);
        if (online != null) {
            return online;
        }
        return s().counters.names().nameOf(uuid).orElse(uuid.toString().substring(0, 8));
    }

    /** {@code kind at world player summary} for one journal line or event. */
    String line(String kind, long at, String world, UUID player, JsonObject json) {
        return kind + " " + TIME.format(Instant.ofEpochMilli(at)) + " " + (world == null ? "-" : world) + " "
                + nameOf(player) + " " + summary(json);
    }

    /** The fields of an event after the header, as {@code name=value}, cut at 120 characters. */
    static String summary(JsonObject o) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            if (k.equals("kind") || k.equals("at") || k.equals("seq") || k.equals("world") || k.equals("player")) {
                continue;
            }
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k).append('=').append(compact(v));
            if (sb.length() > 120) {
                sb.setLength(117);
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }

    private static String compact(JsonElement v) {
        if (v.isJsonPrimitive()) {
            return v.getAsString();
        }
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            for (String key : new String[]{"name", "typeId", "kind"}) {
                JsonElement inner = o.get(key);
                if (inner != null && inner.isJsonPrimitive()) {
                    return inner.getAsString();
                }
            }
            return "{}";
        }
        return v.isJsonArray() ? "[" + v.getAsJsonArray().size() + "]" : v.toString();
    }

    static Set<EventKind> parseKinds(String text, List<String> unknown) {
        EnumSet<EventKind> out = EnumSet.noneOf(EventKind.class);
        for (String part : text.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            EventKind k = EventKind.byId(part);
            if (k == null) {
                unknown.add(part.trim());
            } else {
                out.add(k);
            }
        }
        return out;
    }

    static int clamp(Integer v, int fallback, int min, int max) {
        return v == null ? fallback : Math.max(min, Math.min(max, v));
    }

    static String duration(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long days = minutes / (60 * 24);
        long hours = minutes / 60 % 24;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + mins + "m";
        }
        return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    // ---- status ------------------------------------------------------------------------------------------

    static final class Status extends CommandBase {
        private final TrackerCommand root;

        Status(TrackerCommand root) {
            super("status", "Version, uptime, subscriptions, queue and journal.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
        }

        @Override
        protected void executeSync(CommandContext context) {
            replyLines(context, root.statusLines());
        }
    }

    List<String> statusLines() {
        Services s = s();
        List<String> lines = new ArrayList<>();
        lines.add("TrackerAPI " + plugin.version() + ", up " + duration(System.currentTimeMillis() - startedAt) + ", "
                + s.players.count() + " players online, " + s.counters.loadedCount() + " counter files loaded.");
        long drops = 0;
        for (EventKind k : EventKind.values()) {
            drops += s.publisher.dropped(k);
        }
        lines.add("Tracking " + s.config().trackedKinds().size() + " of " + EventKind.COUNT + " kinds. Async queue "
                + s.publisher.queued() + " of " + s.publisher.queueCapacity() + ", " + drops + " dropped.");
        JournalService j = s.journal();
        if (j == null) {
            lines.add("The journal is off.");
        } else {
            lines.add("Journal " + j.kinds().size() + " kinds, " + j.linesWritten() + " lines written, " + j.pending()
                    + " pending, " + j.dropped() + " dropped, " + megabytes(j.bytesOnDisk()) + " on disk.");
        }
        long built = s.counters.boards().builtAt();
        lines.add(built <= 0 ? "Boards are not built yet."
                : "Boards built " + duration(System.currentTimeMillis() - built) + " ago over "
                + s.counters.boards().players() + " players.");
        var mods = s.mods();
        if (mods != null) {
            lines.add(mods.statusLine());
        }
        List<String> users = plugin.dependentNames();
        lines.add(users.isEmpty() ? "No enabled plugin lists TrackerAPI." : "Used by " + String.join(", ", users) + ".");
        List<Subscription> subs = s.publisher.subscriptions();
        lines.add(subs.isEmpty() ? "No subscriptions." : subs.size() + " subscriptions.");
        for (Subscription sub : subs) {
            lines.add("  " + sub + ", delivered " + sub.delivered() + ", failures " + sub.failures()
                    + (sub.isSuspended() ? ", suspended" : "") + (sub.isActive() ? "" : ", closed"));
        }
        return lines;
    }

    // ---- mods --------------------------------------------------------------------------------------------

    static final class Mods extends CommandBase {
        private final TrackerCommand root;

        Mods(TrackerCommand root) {
            super("mods", "The other mods TrackerAPI reads, bound or not, with their features and counters.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
        }

        @Override
        protected void executeSync(CommandContext context) {
            var mods = root.s().mods();
            replyLines(context, mods == null ? List.of("TrackerAPI ships no mod bindings.") : mods.lines());
        }
    }

    // ---- perf --------------------------------------------------------------------------------------------

    static final class PerfCommand extends CommandBase {
        private final TrackerCommand root;

        PerfCommand(TrackerCommand root) {
            super("perf", "What each hook costs, highest first.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            addSubCommand(new PerfReset(root));
        }

        @Override
        protected void executeSync(CommandContext context) {
            replyLines(context, perfLines(root.s().perf));
        }
    }

    static List<String> perfLines(Perf perf) {
        List<Perf.Row> rows = perf.rows(System.nanoTime());
        List<String> lines = new ArrayList<>();
        lines.add(Perf.verdict(rows).line() + ".");
        if (rows.isEmpty()) {
            return lines;
        }
        lines.add(String.format(Locale.ROOT, "%-24s %10s %9s %9s %9s", "name", "calls", "mean us", "max us", "ms/min"));
        int n = 0;
        for (Perf.Row r : rows) {
            if (n++ >= 15) {
                break;
            }
            lines.add(String.format(Locale.ROOT, "%-24s %10d %9.1f %9.1f %9.2f", r.name(), r.calls(), r.meanMicros(),
                    r.maxMicros(), r.msPerMinute()));
        }
        return lines;
    }

    static final class PerfReset extends CommandBase {
        private final TrackerCommand root;

        PerfReset(TrackerCommand root) {
            super("reset", "Start the cost measurements over.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.s().perf.reset();
            context.sendMessage(text("TrackerAPI cost measurements start over now."));
        }
    }

    // ---- recent ------------------------------------------------------------------------------------------

    static final class Recent extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> kind;

        Recent(TrackerCommand root) {
            super("recent", "The last events of a kind, from memory.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.kind = withRequiredArg("kind", "Event kind such as block_broken", ArgTypes.STRING);
            addUsageVariant(new RecentCount(root));
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.recent(context, context.get(kind), 10);
        }
    }

    static final class RecentCount extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> kind;
        private final RequiredArg<Integer> count;

        RecentCount(TrackerCommand root) {
            super("The last n events of a kind, at most 50.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.kind = withRequiredArg("kind", "Event kind such as block_broken", ArgTypes.STRING);
            this.count = withRequiredArg("n", "How many, 1 to 50", ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.recent(context, context.get(kind), clamp(context.get(count), 10, 1, 50));
        }
    }

    void recent(CommandContext context, String kindText, int n) {
        EventKind kind = EventKind.byId(kindText);
        if (kind == null) {
            context.sendMessage(error("There is no kind called " + kindText + ". Kinds look like block_broken or entity_died."));
            return;
        }
        if (ring.arm(kind)) {
            s().publisher.rebuild();
        }
        List<TrackerEvent> events = ring.last(kind, n);
        if (events.isEmpty()) {
            context.sendMessage(text(s().publisher.isTracked(kind)
                    ? "No " + kind.id() + " events seen yet. TrackerAPI keeps the last " + RecentRing.SIZE + " from now on."
                    : kind.id() + " is turned off in TrackerAPI.json."));
            return;
        }
        List<String> lines = new ArrayList<>(events.size() + 1);
        lines.add("Last " + events.size() + " of " + ring.seen(kind) + " " + kind.id() + " events, newest first.");
        for (TrackerEvent e : events) {
            JsonObject body;
            try {
                body = EventJson.body(e);
            } catch (RuntimeException ex) {
                body = new JsonObject();
            }
            lines.add(line(kind.id(), e.at(), e.world(), e.player(), body));
        }
        replyLines(context, lines);
    }

    // ---- lookup ------------------------------------------------------------------------------------------

    static final class Lookup extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> target;
        private final OptionalArg<Integer> radius;
        private final OptionalArg<String> kinds;
        private final OptionalArg<Integer> hours;
        private final OptionalArg<Integer> limit;

        Lookup(TrackerCommand root) {
            super("lookup", "Search the journal for a player, or around you with here.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.target = withRequiredArg("player", "Player name or uuid, or here for your position", ArgTypes.STRING);
            this.radius = withOptionalArg("radius", "Blocks around the position, 16 for here", ArgTypes.INTEGER);
            this.kinds = withOptionalArg("kinds", "Kinds joined with commas, such as block_broken,block_placed", ArgTypes.STRING);
            this.hours = withOptionalArg("hours", "How far back, default 24", ArgTypes.INTEGER);
            this.limit = withOptionalArg("limit", "How many lines, default 20, at most 200", ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext context) {
            Integer r = context.provided(radius) ? context.get(radius) : null;
            String k = context.provided(kinds) ? context.get(kinds) : null;
            Integer h = context.provided(hours) ? context.get(hours) : null;
            Integer l = context.provided(limit) ? context.get(limit) : null;
            root.lookup(context, context.get(target), r, k, h, l);
        }
    }

    void lookup(CommandContext context, String who, Integer radius, String kindsText, Integer hoursArg, Integer limitArg) {
        JournalService journal = s().journal();
        if (journal == null) {
            context.sendMessage(error("The journal is off on this server. Turn it on in TrackerAPI.json and restart."));
            return;
        }
        int hours = clamp(hoursArg, 24, 1, 24 * 3650);
        int limit = clamp(limitArg, 20, 1, 200);
        Journal.Query base = Journal.query().last(TimeUnit.HOURS.toMillis(hours)).limit(limit);
        if (kindsText != null) {
            List<String> unknown = new ArrayList<>();
            Set<EventKind> ks = parseKinds(kindsText, unknown);
            if (!unknown.isEmpty()) {
                context.sendMessage(error("Unknown kinds " + String.join(", ", unknown) + ". Kinds look like block_broken."));
                return;
            }
            if (!ks.isEmpty()) {
                base = base.withKinds(ks);
            }
        }
        String span = " in the last " + hours + (hours == 1 ? " hour" : " hours");
        if (who.trim().equalsIgnoreCase("here")) {
            UUID me = senderUuid(context);
            if (me == null) {
                context.sendMessage(error("lookup here needs a player. From the console name a player instead."));
                return;
            }
            PlayerSnapshot snap = s().players.snapshot(me);
            if (snap == null) {
                context.sendMessage(error("Your position has not been sampled yet. Try again in a second."));
                return;
            }
            int r = clamp(radius, 16, 0, 512);
            Journal.Query q = base.withWorld(snap.world()).near((int) Math.floor(snap.x()), (int) Math.floor(snap.y()),
                    (int) Math.floor(snap.z()), r);
            String title = "within " + r + " blocks of you" + span;
            replyLater(context, journal.find(q).thenApply(found -> lookupLines(title, found)));
            return;
        }
        Journal.Query finalBase = base;
        replyLater(context, find(who).thenCompose(t -> {
            if (t == null) {
                return CompletableFuture.completedFuture(List.of("TrackerAPI has never seen a player called " + who + "."));
            }
            Journal.Query q = finalBase.withPlayer(t.uuid());
            String title = "for " + t.name();
            if (radius != null) {
                PlayerSnapshot snap = s().players.snapshot(t.uuid());
                if (snap == null) {
                    return CompletableFuture.completedFuture(List.of(t.name() + " is not online, so --radius has no centre. Leave it out."));
                }
                int r = clamp(radius, 16, 0, 512);
                q = q.withWorld(snap.world()).near((int) Math.floor(snap.x()), (int) Math.floor(snap.y()),
                        (int) Math.floor(snap.z()), r);
                title += " within " + r + " blocks of where they stand";
            }
            String fullTitle = title + span;
            return journal.find(q).thenApply(found -> lookupLines(fullTitle, found));
        }));
    }

    private List<String> lookupLines(String title, List<Journal.Entry> found) {
        List<String> lines = new ArrayList<>(found.size() + 1);
        lines.add(found.isEmpty() ? "No journal entries " + title + "."
                : found.size() + " journal entries " + title + ", newest first.");
        for (Journal.Entry e : found) {
            JsonObject o;
            try {
                JsonElement parsed = JsonParser.parseString(e.json());
                o = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (RuntimeException ex) {
                o = new JsonObject();
            }
            lines.add(line(e.kind().id(), e.at(), e.world(), e.player(), o));
        }
        return lines;
    }

    // ---- counters ----------------------------------------------------------------------------------------

    static final class CountersCommand extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> player;

        CountersCommand(TrackerCommand root) {
            super("counters", "A player's counters, online or from disk.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.player = withRequiredArg("player", "Player name or uuid", ArgTypes.STRING);
            addUsageVariant(new CountersFiltered(root));
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.counters(context, context.get(player), null);
        }
    }

    static final class CountersFiltered extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> player;
        private final RequiredArg<String> filter;

        CountersFiltered(TrackerCommand root) {
            super("A player's counters whose keys start with a prefix.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.player = withRequiredArg("player", "Player name or uuid", ArgTypes.STRING);
            this.filter = withRequiredArg("filter", "Key prefix such as block_broken", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.counters(context, context.get(player), context.get(filter));
        }
    }

    void counters(CommandContext context, String who, String filter) {
        String prefix = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        replyLater(context, find(who).thenCompose(t -> {
            if (t == null) {
                return CompletableFuture.completedFuture(List.of("TrackerAPI has never seen a player called " + who + "."));
            }
            CompletableFuture<Map<String, Long>> values = s().counters.isLoaded(t.uuid())
                    ? CompletableFuture.completedFuture(s().counters.all(t.uuid()))
                    : s().counters.load(t.uuid());
            return values.thenApply(all -> {
                TreeMap<String, Long> sorted = new TreeMap<>();
                for (Map.Entry<String, Long> e : all.entrySet()) {
                    if (e.getKey().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        sorted.put(e.getKey(), e.getValue());
                    }
                }
                List<String> lines = new ArrayList<>();
                String about = t.name() + (prefix.isEmpty() ? "" : " starting with " + prefix);
                if (sorted.isEmpty()) {
                    lines.add("No counters for " + about + ".");
                    return lines;
                }
                lines.add(sorted.size() + " counters for " + about + (sorted.size() > 40 ? ", the first 40." : "."));
                int n = 0;
                for (Map.Entry<String, Long> e : sorted.entrySet()) {
                    if (n++ >= 40) {
                        break;
                    }
                    lines.add("  " + e.getKey() + " " + e.getValue());
                }
                return lines;
            });
        }));
    }

    // ---- top ---------------------------------------------------------------------------------------------

    static final class Top extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> key;

        Top(TrackerCommand root) {
            super("top", "The leaderboard of a counter key.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.key = withRequiredArg("key", "Counter key such as block_broken", ArgTypes.STRING);
            addUsageVariant(new TopCount(root));
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.top(context, context.get(key), 10);
        }
    }

    static final class TopCount extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> key;
        private final RequiredArg<Integer> count;

        TopCount(TrackerCommand root) {
            super("The top n players of a counter key.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.key = withRequiredArg("key", "Counter key such as block_broken", ArgTypes.STRING);
            this.count = withRequiredArg("n", "How many, 1 to 50", ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.top(context, context.get(key), clamp(context.get(count), 10, 1, 50));
        }
    }

    void top(CommandContext context, String keyText, int n) {
        String key = keyText.trim();
        List<Map.Entry<UUID, Long>> rows = s().counters.top(key, n);
        if (rows.isEmpty()) {
            context.sendMessage(text(s().counters.boards().builtAt() <= 0
                    ? "The boards are not built yet. They rebuild every " + s().config().boardSeconds + " seconds."
                    : "No board for " + key + ". Boards keep every total and the keys plugins asked for."));
            return;
        }
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("Top " + rows.size() + " for " + key + ".");
        int rank = 1;
        for (Map.Entry<UUID, Long> e : rows) {
            lines.add("  " + rank++ + ". " + nameOf(e.getKey()) + " " + e.getValue());
        }
        replyLines(context, lines);
    }

    // ---- import ------------------------------------------------------------------------------------------

    static final class Import extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> source;
        private final FlagArg force;

        Import(TrackerCommand root) {
            super("import", "Seed the counters from the Feats or Chronicle Stats player files.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.source = withRequiredArg("source", "feats, chronicle or all", ArgTypes.STRING);
            this.force = withFlagArg("force", "Run again after an earlier import");
        }

        @Override
        protected void executeSync(CommandContext context) {
            String what = context.get(source).trim().toLowerCase(Locale.ROOT);
            boolean again = Boolean.TRUE.equals(context.get(force));
            Importer importer = new Importer(root.s());
            CompletableFuture<String> run = switch (what) {
                case "feats" -> importer.runFeats(again);
                case "chronicle" -> importer.runChronicle(again);
                case "all" -> importer.runAll(again);
                default -> null;
            };
            if (run == null) {
                context.sendMessage(error("Use /trackerapi import feats, chronicle or all, with --force to run it again."));
                return;
            }
            context.sendMessage(text("TrackerAPI is importing from " + what + "."));
            root.replyLater(context, run.thenApply(List::of));
        }
    }

    // ---- reload ------------------------------------------------------------------------------------------

    static final class Reload extends CommandBase {
        private final TrackerCommand root;

        Reload(TrackerCommand root) {
            super("reload", "Re-read TrackerAPI.json.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
        }

        @Override
        protected void executeSync(CommandContext context) {
            root.replyLater(context, root.plugin.reload().thenApply(List::of));
        }
    }

    // ---- debug -------------------------------------------------------------------------------------------

    static final class Debug extends AbstractCommandCollection {
        Debug(TrackerCommand root) {
            super("debug", "Synthetic events for smoke tests, with debugCommands on.");
            requirePermission(ADMIN_PERMISSION);
            addSubCommand(new DebugPublish(root));
            addSubCommand(new DebugFlood(root));
        }
    }

    boolean debugAllowed(CommandContext context) {
        if (s().config().debugCommands) {
            return true;
        }
        context.sendMessage(error("TrackerAPI debug commands are off. Set debugCommands to true in TrackerAPI.json and run /trackerapi reload."));
        return false;
    }

    static final class DebugPublish extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> kind;

        DebugPublish(TrackerCommand root) {
            super("publish", "Publish one synthetic event of a kind.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.kind = withRequiredArg("kind", "Event kind such as block_broken", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (root.debugAllowed(context)) {
                root.flood(context, context.get(kind), 1);
            }
        }
    }

    static final class DebugFlood extends CommandBase {
        private final TrackerCommand root;
        private final RequiredArg<String> kind;
        private final RequiredArg<Integer> count;

        DebugFlood(TrackerCommand root) {
            super("flood", "Publish many synthetic events of a kind.");
            requirePermission(ADMIN_PERMISSION);
            this.root = root;
            this.kind = withRequiredArg("kind", "Event kind such as block_broken", ArgTypes.STRING);
            this.count = withRequiredArg("n", "How many, 1 to 1000000", ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (root.debugAllowed(context)) {
                root.flood(context, context.get(kind), clamp(context.get(count), 1, 1, 1_000_000));
            }
        }
    }

    /**
     * Publishes {@code n} synthetic events on a short lived thread of its own, so a flood never holds a
     * world tick or the timer. The events carry a synthetic player who is never online, so they reach
     * listeners and the journal but never the sender's real counters.
     */
    void flood(CommandContext context, String kindText, int n) {
        EventKind kind = EventKind.byId(kindText);
        if (kind == null) {
            context.sendMessage(error("There is no kind called " + kindText + "."));
            return;
        }
        Constructor<?> ctor = Synthetic.constructor(kind);
        if (ctor == null) {
            context.sendMessage(error("TrackerAPI has no event record for " + kind.id() + "."));
            return;
        }
        UUID uuid = DEBUG_UUID;
        String name = "Debug";
        String world = "debug";
        Services s = s();
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                boolean wanted = s.publisher.wants(kind);
                long start = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    s.publisher.publish(Synthetic.build(ctor, s.header(world, uuid), uuid, name));
                }
                long took = System.nanoTime() - start;
                String head = "Published " + n + " synthetic " + kind.id() + (n == 1 ? " event" : " events")
                        + String.format(Locale.ROOT, " in %.1f ms, %d ns each.", took / 1e6, took / Math.max(1, n));
                done.complete(wanted ? List.of(head)
                        : List.of(head, "Nothing wants " + kind.id() + " right now, so the events went nowhere."));
            } catch (RuntimeException | LinkageError e) {
                done.completeExceptionally(e);
            }
        }, "TrackerAPI-flood");
        worker.setDaemon(true);
        worker.start();
        replyLater(context, done);
    }

    /** Builds an event record of any kind with plain placeholder values. */
    static final class Synthetic {
        private Synthetic() {
        }

        static Constructor<?> constructor(EventKind kind) {
            StringBuilder sb = new StringBuilder("dev.ryvrook.trackerapi.api.event.");
            for (String part : kind.name().split("_")) {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
            try {
                Class<?> type = Class.forName(sb.toString(), false, TrackerCommand.class.getClassLoader());
                if (!type.isRecord()) {
                    return null;
                }
                RecordComponent[] rc = type.getRecordComponents();
                Class<?>[] types = new Class<?>[rc.length];
                for (int i = 0; i < rc.length; i++) {
                    types[i] = rc[i].getType();
                }
                return type.getDeclaredConstructor(types);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        static TrackerEvent build(Constructor<?> ctor, Header header, UUID uuid, String name) {
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                args[i] = placeholder(types[i], header, uuid, name);
            }
            try {
                return (TrackerEvent) ctor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private static Object placeholder(Class<?> t, Header header, UUID uuid, String name) {
            if (t == Header.class) {
                return header;
            }
            if (t == String.class) {
                return "Debug";
            }
            if (t == int.class) {
                return 0;
            }
            if (t == long.class) {
                return 1L;
            }
            if (t == double.class) {
                return 0.0;
            }
            if (t == float.class) {
                return 0f;
            }
            if (t == boolean.class) {
                return false;
            }
            if (t == short.class) {
                return (short) 0;
            }
            if (t == byte.class) {
                return (byte) 0;
            }
            if (t == UUID.class) {
                return uuid;
            }
            if (t == Actor.class) {
                return Actor.player(uuid, name);
            }
            if (t.isEnum()) {
                Object[] values = t.getEnumConstants();
                return values.length == 0 ? null : values[0];
            }
            if (t == List.class || t == java.util.Collection.class) {
                return List.of();
            }
            if (t == Set.class) {
                return Set.of();
            }
            if (t == Map.class) {
                return Map.of();
            }
            return null;
        }
    }
}
