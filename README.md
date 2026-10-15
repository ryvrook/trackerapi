# TrackerAPI

TrackerAPI hooks the engine once for every block, item, hit, death, movement, chat line and world change, and
hands the result to other plugins as 62 kinds of event, lifetime counters per player and an hourly journal on
disk. On its own it does nothing a player sees. It exists so Feats, Chronicle Stats, Atlas, Coinage and Dominion
stop running five copies of the same tracking and stop disagreeing about the numbers.

## Requirements

Hytale server 0.7.0 release or newer, tested on 0.7.1, and Java 25. No client mod and no other plugin needed.

## Install

Put `TrackerAPI-1.1.0.jar` in the server's `mods` folder and start the server, or run
`/plugin load Ryvrook:TrackerAPI`. The log should say
`TrackerAPI 1.1.0 set up, 53 hooks registered, 0 unsupported, 62 kinds tracked, journal on`. Settings go to
`mods/Ryvrook_TrackerAPI/TrackerAPI.json`.

On first boot it reads the Feats and Chronicle Stats player files it finds next door and seeds its counters
from them, taking the larger value where both counted the same thing. `/trackerapi import all --force` does it
again.

## Commands

Every command needs `trackerapi.admin`. The console has it.

| Command | What it does |
|---|---|
| `/trackerapi status` | Version, uptime, players loaded, every subscription with owner, kinds and delivery, queue depth, drops, journal size |
| `/trackerapi perf` | What each hook costs on the engine threads, highest first, with a verdict. `perf reset` starts over |
| `/trackerapi recent <kind> [n]` | The last events of a kind, from memory |
| `/trackerapi lookup <player or here> [--radius n] [--kinds a,b] [--hours n] [--limit n]` | Search the journal |
| `/trackerapi counters <player> [prefix]` | A player's counters, online or from disk |
| `/trackerapi top <key> [n]` | The leaderboard of a counter |
| `/trackerapi import <feats or chronicle or all> [--force]` | Seed counters from the other mods' files again |
| `/trackerapi reload` | Re-read `TrackerAPI.json` |

`/trackerapi lookup here --radius 8 --hours 12` answers "who broke the blocks around me last night".

## Settings

Missing keys are written back with their defaults. Values out of range are clamped with one warning.

| Key | Default | What it does |
|---|---|---|
| `disabledKinds` | `[]` | Kinds never tracked, by lower case name such as `block_damaged` |
| `countCreative` | `false` | Count block, item and combat events of Creative players |
| `perWorldCounters` | `true` | Keep per world copies of the totals |
| `autosaveSeconds` | `60` | Counter save interval, 10 to 3600 |
| `boardSeconds` | `60` | Leaderboard rebuild interval, `0` turns boards off |
| `queueCapacity` | `65536` | Async queue size. Events past it are dropped and counted |
| `listenerBudgetMillis` | `50` | Time a listener gets per batch before a warning. Twenty batches over in a row suspend it for five minutes |
| `sampleSeconds` | `1` | Seconds between position samples |
| `vitalsSeconds` | `0` | Seconds between health, stamina and mana samples, `0` is off |
| `chatRecordText` | `false` | Keep chat text, otherwise only its length |
| `journal.enabled` | `true` | Keep the on disk journal |
| `journal.kinds` | every kind that is not high volume | Kinds written to the journal |
| `journal.retentionDays` | `14` | Days of journal kept |
| `journal.maxMegabytes` | `1024` | Size cap, oldest files go first |
| `importOnFirstBoot` | `true` | Read the Feats and Chronicle Stats files once |
| `debugCommands` | `false` | Turn on `/trackerapi debug` for smoke tests |

## With the other mods

Each consumer lists `Ryvrook:TrackerAPI` as an optional dependency and has a config key, `Tracker` in Feats and
`tracker` in the others, set to `auto`, `always` or `never`. On `auto` it uses TrackerAPI when present and
tracks on its own when not.

**Feats 1.5.0 and newer** drops its own tracking systems and feeds 19 kinds into its feats. Counts carry on in
the Feats files without a gap.

**Chronicle Stats 1.2.0 and newer** takes 35 kinds and the player snapshots for its stats, records and web
dashboard, and shows "TrackerAPI connected" at the top right of the dashboard.

**Atlas 1.6.0 and newer** builds its activity heat layer and kill feed from block and death events.

**Coinage 1.0.0 and newer** pays kill and block rewards from `EntityDied` and `BlockBroken`, and writes
`coinage:earned:<currency>` and `coinage:spent:<currency>` counters back.

**Dominion 0.5.1** feeds its gather missions from block broken, block placed, item picked up and item dropped,
and writes ten `dominion:` counters such as `dominion:claims` and `dominion:sieges_won`, each with a
leaderboard.

Lullaby does not use it.

A `/plugin reload` of TrackerAPI leaves those plugins linked to the old classes, so two seconds after it starts
again TrackerAPI reloads each of them in dependency order, Coinage before Chronicle Stats and Dominion. Reload
TrackerAPI and the rest follows.

## For plugin authors

Compile against the jar as `compileOnly`, list it as optional, and keep every TrackerAPI import in one class you
load only after `TrackerApi.isPresent()` is true. Your plugin then still loads without it.

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

`onEnable` runs at once when TrackerAPI is already up and again each time it starts. Subscriptions close by
themselves when it stops. Listeners run on the `TrackerAPI-dispatch` thread within the budget. One that needs
engine state in the same tick subscribes with `Delivery.SYNC` and has to return in microseconds.

Every record has `kind()`, `at()`, `seq()`, `world()` and `player()` plus its own fields. Records only gain
fields, so use the accessors and keep a default branch when you switch on the kind. The kinds are the
`EventKind` enum, one record each under `dev.ryvrook.trackerapi.api.event`.

Counter keys follow `Keys`. A total is `block_broken`, a per id count is `block_broken:Rock_Stone` or
`kill:Goblin_Scrapper`, a per world copy is `world:default:block_broken`. You can write your own keys under your
plugin's prefix, such as `myplugin:points`. A write for an offline player lands in their file on the IO thread a
moment later. `counters().watchBoard(key)` gives you a leaderboard for it.

## Cost

A hook whose kind nobody wants costs one volatile read. A wanted kind builds one small record on the world
thread and passes it to the dispatch thread through a lock free queue. With the 60 subscriptions the whole suite
opens, the dispatch thread spends 61 to 69 ns per block event. A slow listener cannot slow the tick, it only
gets warned and then suspended. `/trackerapi perf` shows the real numbers on your server.

The journal is most of the disk use. Damage, position samples, inventory moves and chunk loads stay out of it
by default, and `journal.maxMegabytes` caps the rest.

## Limits

- The headless smoke test has no players, so it cannot show anything player driven. TrackerAPI runs on my own
  server beside Feats, Chronicle Stats and Atlas, which is where 1.0.0 crashed a world within a minute of players
  joining (fixed in 1.0.1). The volume of the high volume kinds on a big server is still unmeasured.
- `PREFAB_PASTED` is in the API and never published. Worldgen pastes prefabs from its own threads and hooking
  that event crashes the world. Chunk unloads come from the chunk store callback for the same reason.
- TrackerAPI only observes. It never cancels or changes an event.

## Building

JDK 25. `./gradlew build` puts the jar in `build/libs/` and runs the tests. The headless smoke recipe is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), with the consumer table and the reload rule every reloader in the
suite follows.

## Licence

MIT, see [LICENSE](LICENSE).
