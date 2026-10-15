# TrackerAPI development notes

TrackerAPI is built against Hytale server 0.7.1 with Java 25. Plugin id `Ryvrook:TrackerAPI`, main class `dev.ryvrook.trackerapi.TrackerApiPlugin`, data folder `mods/Ryvrook_TrackerAPI`, jar `TrackerAPI-<version>.jar`. This file is the maintainer's reference. It covers the build, the packages, the threads, the hot path rule, the data folder, the config, the commands, the API contract, the headless smoke test and what cannot be verified without a client.

## Build and test

```
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew build
./gradlew test -Pbench        prints the publishing and dispatch benchmarks
./gradlew installMod          copies the jar into the local Hytale UserData/Mods folder
```

The server artifact comes from `maven.hytale.com` as `compileOnly`, with the channel and version in `gradle.properties` (`-Phytale_channel=release -Phytale_version=0.7.0` overrides them). The same jar sits on the test classpath for Gson, the logger and marker types. No test starts an engine. The version is set once in `build.gradle.kts` and reaches the manifest through `processResources`.

## Packages

| Package | What lives there |
|---|---|
| `api` | The public contract. `TrackerApi` (the entry point and the static enable and disable hooks), `TrackerEvent` and `Header`, `EventKind`, `Actor`, `Delivery`, `Subscription`, `Counters` and `Keys`, `PlayerSnapshot`, `Journal`, `Stats`. Methods and record components are only ever added. |
| `api.event` | One record per kind, 62 of them. Plain values only, never engine objects. |
| `bus` | `Publisher` (the want flags, sequence numbers, sinks, sync delivery with suspension), `MpmcQueue` (bounded lock free, Vyukov), `Dispatcher` (the async thread, batches of 512, a listener with none of its kinds in a batch is skipped whole and that batch neither adds to nor resets its run over budget, budget per listener, suspension after 20 batches over budget), `SubscriptionImpl`. |
| `counters` | `CounterStore` (online players in memory, files on IO, join and leave, autosave), `PlayerCounters`, `ServerCounters`, `NamesIndex`, `Boards`, `CounterSink` (the rulebook that turns events into keys), `CounterRules` (which keys are bests). |
| `facts` | What the hooks compute once so every consumer sees the same numbers. `PlayerRegistry`, `Sampler`, `MovementSampler`, `MoveBits`, `PlacedBlocks`, `RecentPlacements`, `RecentDrops`, `RecentStash`, `RecentContainers`, `BossLedger`, `Bosses`, `TameWatch`, `KillStreaks`, `WorldTasks`, `Forgetting`. |
| `hooks` | `HookSet`, `Registrar` (guarded registration with straggler cleanup), `Hooks` (the install order), `PlayerEventSystem` and the five sets `PlayerHooks`, `BlockHooks`, `ItemHooks` with `InventoryHook`, `CombatHooks` (subpackage `combat`), `WorldHooks` (subpackage `world`), plus `MovementEdgeSystem`. |
| `journal` | `FileJournal`, `EventJson`, `JournalService`. |
| `imports` | `Importer`, `FeatsImport`, `ChronicleImport`, `ImportState`. |
| `command` | `TrackerCommand` and `RecentRing`. |
| `util` | `Guard`, `Perf`, `Threads`, `JsonFiles`. |

`Services` is the hub every hook reads its neighbours from. `TrackerConfig` is the config. `TrackerApiImpl` is the API over the services.

## Threads

| Thread | What runs there |
|---|---|
| World threads | Every ECS system and world keyed bus event, sync listeners, the counter sink and the journal `accept` (which only enqueues), the sampler and clock tasks posted through `WorldTasks`. |
| Network thread | Chat (async, LATE), disconnect, the command packet watcher. |
| `TrackerAPI-dispatch` | Async listener batches. Parks when idle, unparked by the publisher. |
| `TrackerAPI-io` | Every file read and write, the journal flush, prune and queries, the import, the board rebuild. |
| `TrackerAPI-timer` | The sampler and clock posts (they only call `WorldTasks.Gate.post`), playtime, autosave and board timers, the offline grace sweep. |

Components are touched only on the owning world thread. `PlayerRef.getTransform()` and `getComponentConcurrent` are the sanctioned off thread reads. Nothing uses `HytaleServer.SCHEDULED_EXECUTOR`. Every thread is a daemon owned by the plugin instance, so a reload stops the old ones and starts new ones. Verified on a headless 0.7.1 server on 2026-10-14, a reload left exactly one of each thread.

## The hot path rule

Every hook does `if (!publisher.wants(kind)) return;` before it reads the engine or allocates, then builds its record with `services.header(world, player)` and calls `publisher.publish`. The want flags are a `volatile byte[]` indexed by kind, rebuilt whenever a subscription opens or closes, a sink changes or the config turns a kind off. Because the counter sink wants most kinds the busy hooks always build their record, so they stay cheap. Id to key work is memoised in `Keys.of`, per player state sits in concurrent maps keyed by uuid, and ticking systems allocate nothing on the steady path.

Cancelled engine events are never published. Creative is published with a flag on the block events, and the counter sink skips Creative unless `countCreative` is on.

Every handler body is guarded. A failure is logged at most once a minute through `Guard.failed` and the cost lands in the `Perf` slot of its kind. `/trackerapi perf` prints the slots with a verdict (fine under 300 ms per minute on engine threads, watch to 1,500, too costly past that, and watch when one call took over 20 ms).

## Data folder

```
mods/Ryvrook_TrackerAPI/
├── TrackerAPI.json                  the config, every key with its default
├── players/<uuid>.json              one file per player, Version 1, uuid, name, firstSeen, lastSeen, counters, imported
├── server/totals.json               server wide counters and since
├── server/import-state.json         what the import read and when
├── names.json                       uuid to last name
└── journal/<world or _server>/      yyyy-MM-dd-HH.jsonl for the open hour, .jsonl.gz for closed hours
```

Every write goes through a temp file and a move. A file that does not parse is moved aside as `<name>.corrupt-<millis>.json` and the player starts from zero, with one warning.

Counter keys. A total such as `block_broken`, a per id count such as `block_broken:Rock_Stone`, and a per world copy of the totals in `Keys.PER_WORLD_TOTALS` as `world:<name>:<total>`. Bests (`biggest_hit`, `kill_streak_best`, `session_longest`, `longest_fall`, `highest_y`) merge by maximum, `lowest_y` by minimum, everything else by sum. Other plugins may write under their own prefix through `Counters.add` and `raiseTo`, and `Keys.isExternal` refuses the reserved prefixes. Those writes feed the server totals too. A write for an offline player is queued, and one IO task applies the whole queue, to the live object when the player came back, to the leaving object when its save is still queued, to the file otherwise, and drops it when there is no file. Repeated writes of one key fold into one entry while they wait, and the queue takes at most 10,000 players, past that `add` answers false until IO catches up. A plugin key raised before a join's file read landed merges as the larger value, not the sum. Leaderboards hold every bare total and the keys a plugin asked for with `watchBoard`, Dominion asks for five of its own.

## Config

`TrackerAPI.json`, Gson. Missing keys are written back, out of range values are clamped with one warning, a file that does not parse keeps the defaults at boot and is refused on `/trackerapi reload`. The README lists every key. What changes live on reload: the tracked kinds, the listener budget, per world counters, Creative counting, the journal kinds and limits. What waits for a restart: the queue size, the sample interval, journal on or off.

## Commands

`/trackerapi` with sub commands status, perf, perf reset, recent, lookup, counters, top, import, reload, and debug publish and debug flood behind `debugCommands`. Every node requires `trackerapi.admin`. Replies that wait on a future come back on the sender's world thread. `recent` keeps 256 events per kind in memory for the kinds the counters or the journal want, and starts keeping any other kind the first time it is asked.

## Consumers

Five plugins list `Ryvrook:TrackerAPI` under `OptionalDependencies` with `>=1.0.0` and keep every TrackerAPI import in one bridge class.

| Plugin | What it takes | What it gives back |
|---|---|---|
| Feats 1.5.1 | 19 kinds, one async subscription each, in place of its own systems | nothing |
| Chronicle Stats 1.3.0 | 34 kinds and the snapshots, in place of its own tracking hooks | nothing |
| Atlas 1.6.0 | BLOCK_BROKEN, BLOCK_PLACED, ENTITY_DIED and the snapshots | nothing |
| Coinage 1.1.0 | ENTITY_DIED and BLOCK_BROKEN for the rewards module | `coinage:earned:<currency>` and `coinage:spent:<currency>` |
| Dominion 0.5.1 | nothing | ten `dominion:` counters, five of them watched for boards |

Each decides at setup from the plugin manager whether to register its own systems, and binds from start through `TrackerApi.onEnable`. The engine's `PluginClassLoader` resolves classes of listed dependencies through `PluginManager$PluginBridgeClassLoader`, which walks `Dependencies` then `OptionalDependencies` (verified by javap on 0.7.1). A reload of TrackerAPI leaves consumers linked to the old classes. `Dependents` finds every enabled plugin whose manifest names TrackerAPI and reloads it through `PluginManager.reload` two seconds after start. They go in an order where each comes after the others in the list it names itself, so Feats, Atlas and Coinage come back before Dominion and Chronicle Stats, which read them. `PluginManager` keeps plugins in a linked map and a reloaded plugin moves to its end, so the plain map order is not enough once the owner reloaded one by hand. `PluginManager.unload` only cascades over hard `Dependencies`, which none of the suite uses. `PluginManager.reload` answers false when a plugin's preLoad future is not done yet and finishes the start on that future, which Feats does, so a false is followed by a wait of up to 15 seconds for the new instance to be enabled before the owner is told to reload by hand. A plugin whose instance already changed since TrackerAPI started is left alone. Smoke on 2026-10-15 with every suite jar: the reload went Atlas, Coinage, Feats, Chronicle Stats, Dominion with no warning, and `/trackerapi status` listed the same 58 subscriptions before and after. The consumers still poll the plugin identity every 5 seconds as a fallback.

### The reload rule for every reloader in the suite

TrackerAPI and Coinage both reload their dependents after their own reload, and TrackerAPI's pass reloads Coinage, so without a rule Chronicle Stats and Dominion start twice. Every plugin of the suite that reloads its dependents follows these four steps, and a new reloader copies `Dependents` as it is.

1. At its own start, list the enabled plugins that name it under `Dependencies` or `OptionalDependencies`, and keep each `PluginBase` instance as it is at that moment. Order them so a plugin comes after the others in the list it names.
2. Two seconds later, take the suite lock for the whole pass. The lock is a `ReentrantLock` stored under the key `ryvrook.dependents.lock` in `System.getProperties()`, created with `computeIfAbsent`, the one object every plugin class loader shares. Wait up to 30 seconds for it, then go ahead without it.
3. For each dependent, skip it when `PluginManager.getPlugin(id)` is a different instance from the kept one and is enabled. Someone reloaded it after this reloader started, so it already links to this reloader's new classes.
4. Otherwise call `PluginManager.reload(id)`. When it answers false, poll `getPlugin(id)` every 100 ms for up to 15 seconds for a new enabled instance before warning.

A time window alone is not enough. A plugin reloaded a second before this reloader started still links to the old classes and must be reloaded again, and only the kept instance tells the two cases apart. Coinage carries the same class on its review branch. Smoke on 2026-10-15 with TrackerAPI, Coinage, Atlas, Dominion, Chronicle Stats and Feats, then `plugin reload Ryvrook:TrackerAPI`: every plugin started once after the reload. TrackerAPI's pass held the lock and reloaded Atlas, Coinage, Feats, Chronicle Stats and Dominion, then Coinage's pass found Chronicle Stats and Dominion on new instances and left them alone. Both relinked to the new Coinage. With a Coinage jar that lacks the rule, Chronicle Stats and Dominion started twice.

The dispatch benchmark opens the subscriptions the suite opens, 58 of them, and drains a million block events at about 55 ns an event on the dispatch thread. Before batches skipped listeners with none of their kinds it was about 125 ns.

## Headless smoke test

1. `./gradlew build`, make a scratch folder with `permissions.json` holding `{"users":{},"groups":{}}` and a `mods` folder with the jar.
2. Run `HytaleServer.jar` from the game install with `--assets <Assets.zip> --auth-mode offline --disable-sentry --bind 127.0.0.1:<port>`, stdin from a named pipe. Do not pass `--bare` (the server dies on 0.7.1) and do not pass `--mods` for the same folder (the plugin loads twice).
3. Expect `TrackerAPI <version> set up, 53 hooks registered, 0 unsupported, 62 kinds tracked, journal on` and `TrackerAPI <version> started`.
4. Pipe `trackerapi status`, `trackerapi perf`, `trackerapi recent world_added`. Expect a journal line for `world_added`, `moon_phase_changed` and `world_event_started` under `journal/default/` and `server_lifecycle` under `journal/_server/`.
5. Pipe `plugin reload Ryvrook:TrackerAPI`, expect the same two lines again and `jcmd <pid> Thread.print` to show one of each TrackerAPI thread. Pipe `stop`, expect no exception from TrackerAPI.

Smoke runs on 2026-10-14 passed all of the above on 0.7.1.

## Not verified without a client

No player has joined a TrackerAPI server yet. Everything player driven (block, item, combat, movement, chat and command kinds, the counters they feed, the consumer bridges) has unit tests over synthetic records and ported engine reads that Feats and Chronicle run live, but the first real session is still to come. What only a live server can confirm is mostly that the damage system's ordering after the filter group registers and gives the final amounts, when the projectile hit chain fires and what it carries, whether ITEM_EQUIPPED and ITEM_BROKE arrive as the inventory transactions assumed, the respawn position timing, the order of PlayerReady and AddPlayerToWorld on a first join, and the real volume of the high volume kinds.
