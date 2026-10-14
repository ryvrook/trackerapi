# TrackerAPI

A shared event tracker for Hytale servers.

TrackerAPI records every interaction the server exposes once, on one set of engine hooks, and hands it to other plugins. Blocks broken and placed, items crafted, picked up, gained, lost, eaten and dropped, every hit and every death with who took part, creatures spawned and tamed, players joining, moving, teleporting, chatting and changing worlds, zones and memories, mounts, effects, world events, weather, moon phases, chunks and more. Plugins that need any of that subscribe to TrackerAPI instead of hooking the engine themselves, so a server with several stats, achievement, leveling or map plugins pays for the tracking once.

Everything runs on the server. Players join with an unmodified client.

## What it gives other plugins

- **Live events.** Sixty two kinds of event as plain immutable records, delivered off the world thread in order, or on the world thread for the few consumers that need the same tick. Each record carries the facts the consumer would otherwise compute itself, such as whether a broken block was placed by the same player, how much of a pickup the player had dropped themself, or every player who hit a boss before it died.
- **Counters.** Lifetime counters for every player, kept in one JSON file per player, with totals, per block, item, creature and cause counts, and per world copies. Server totals, a names index and leaderboards over every player who ever joined.
- **Journal.** An on disk record of past events, one line per event in hourly files per world, pruned by age and size, with a query API and a lookup command, so an admin can ask who broke the blocks around a spot last night.

## Installation

Drop `TrackerAPI-<version>.jar` into the server's `mods` folder and start the server, or run `/plugin load Ryvrook:TrackerAPI`. The data folder `mods/Ryvrook_TrackerAPI` is created with `TrackerAPI.json` holding every setting and its default.

On the first boot TrackerAPI looks for the player files of Feats and Chronicle Stats in the neighbouring mod folders and seeds its counters from them, taking the larger value where both counted the same thing, so totals do not start from zero on a server that already ran those mods. Run `/trackerapi import all --force` to do it again.

## Works with

- **Feats 1.5.0 and newer** stops registering its own tracking systems when TrackerAPI is installed and feeds its feats from TrackerAPI events. Counts carry on in the Feats files without a gap.
- **Chronicle Stats 1.2.0 and newer** does the same for its statistics, recorders and web dashboard.
- **Atlas 1.6.0 and newer** takes its activity heat layer and kill feed from TrackerAPI.

Each of them has a config key, `Tracker` in Feats and `tracker` in the others, with `auto`, `always` and `never`. With `auto` the plugin uses TrackerAPI when it is present and tracks on its own when it is not.

## Commands

All of them need the `trackerapi.admin` permission. The console has every permission.

| Command | What it does |
|---|---|
| `/trackerapi status` | Version, uptime, players loaded, every subscription with its owner, kinds and delivery, queue depth, drops, journal size |
| `/trackerapi perf` | Cost of every hook on the engine threads, with a one line verdict, `perf reset` clears it |
| `/trackerapi recent <kind> [n]` | The last events of a kind |
| `/trackerapi lookup <player or here> [--radius n] [--kinds a,b] [--hours n] [--limit n]` | Search the journal |
| `/trackerapi counters <player> [prefix]` | A player's counters |
| `/trackerapi top <key> [n]` | A leaderboard |
| `/trackerapi import <feats or chronicle or all> [--force]` | Read the other mods' player files again |
| `/trackerapi reload` | Read `TrackerAPI.json` again |

## Settings

`mods/Ryvrook_TrackerAPI/TrackerAPI.json`. Missing keys are written back with their defaults, and values outside their range are clamped with one warning.

| Key | Default | Meaning |
|---|---|---|
| `disabledKinds` | `[]` | Kinds that are never tracked, by their lower case name such as `block_damaged` |
| `countCreative` | `false` | Count block, item and combat events of players in Creative mode |
| `perWorldCounters` | `true` | Keep per world copies of the totals |
| `autosaveSeconds` | `60` | How often the counters are saved |
| `boardSeconds` | `60` | How often the leaderboards are rebuilt, 0 turns them off |
| `queueCapacity` | `65536` | Size of the async queue, events past it are dropped and counted |
| `listenerBudgetMillis` | `50` | How long an async listener may take per batch before it is warned, and suspended after twenty such batches in a row |
| `sampleSeconds` | `1` | Seconds between position samples |
| `vitalsSeconds` | `0` | Seconds between health, stamina and mana samples, 0 turns them off |
| `chatRecordText` | `false` | Record the text of chat messages, otherwise only the length |
| `journal.enabled` | `true` | Keep the on disk journal |
| `journal.kinds` | every kind that is not high volume | Kinds written to the journal |
| `journal.retentionDays` | `14` | Days of journal kept |
| `journal.maxMegabytes` | `1024` | Size cap for the journal, oldest files go first |
| `importOnFirstBoot` | `true` | Read the Feats and Chronicle Stats player files once |
| `debugCommands` | `false` | Turn on `/trackerapi debug` |

## For plugin authors

Compile against the TrackerAPI jar as `compileOnly`, list it as an optional dependency in your manifest, and keep every use of its classes in one class that you load only after `TrackerApi.isPresent()` answered true, so your plugin still loads on a server without TrackerAPI.

```json
"OptionalDependencies": { "Ryvrook:TrackerAPI": ">=1.0.0" }
```

```java
TrackerApi.onEnable(api -> {
    api.subscribe("MyPlugin", BlockBroken.class, e -> {
        if (!e.playerPlaced()) {
            counts.merge(e.blockId(), 1L, Long::sum);
        }
    });
    long kills = api.counters().get(player, Keys.KILLS);
});
TrackerApi.onDisable(() -> counts.clear());
```

The enable hook runs at once when TrackerAPI is already up and again whenever it starts, and every subscription is closed for you when it stops. Listeners run on the `TrackerAPI-dispatch` thread by default and may take their time within the budget. A listener that needs engine state in the same tick subscribes with `Delivery.SYNC` and must return in microseconds.

Every event record has `kind()`, `at()`, `seq()`, `world()` and `player()`, plus its own fields. Records only ever gain fields, so read them through their accessors and keep a default branch when you switch on the kind. The full list is the `EventKind` enum and the records under `dev.ryvrook.trackerapi.api.event`.

Counters follow one key scheme in `Keys`. A total such as `block_broken`, a per id count such as `block_broken:Rock_Stone` or `kill:Goblin_Scrapper`, and a per world copy such as `world:default:block_broken`. Your plugin may add counters of its own under its own prefix, such as `myplugin:points`, and they are saved with everything else.

A `/plugin reload` of TrackerAPI leaves its consumers linked to the old instance, so TrackerAPI reloads every enabled plugin that lists it as a dependency two seconds after it starts again. Reload TrackerAPI first and the rest follows.

## Performance

A hook whose kind nobody wants costs one volatile read. A wanted kind builds one small record on the world thread and hands it to the dispatch thread through a lock free queue, which is a few hundred nanoseconds. Counters are a handful of map writes under the player's own lock. Consumers run off the world thread, so a slow consumer never slows the tick, and one that stays over its budget is suspended for five minutes with a warning. `/trackerapi perf` shows what every hook costs on the engine threads, as a mean, a maximum and milliseconds per minute, with a verdict.

Disk use is dominated by the journal. The high volume kinds (damage, every position sample, inventory moves, chunk loads) are left out of it by default, and the size cap keeps the rest bounded.

## License

MIT, see LICENSE.
