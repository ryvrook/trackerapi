# Changelog

## 1.1.0

Players see no change. For admins, a reload of TrackerAPI now brings its dependents back in the right order, each
once. Updating is a jar swap, no setting changed.

- After a reload of TrackerAPI its dependents come back in dependency order, so Coinage is reloaded before
  Chronicle Stats and Dominion, and Feats and Atlas before Dominion. A dependent the server finishes loading a
  moment later, such as Feats, no longer gets a false "could not reload" warning, and one someone else reloaded
  in the meantime is left alone. The pass holds a lock Coinage takes too, so each dependent starts once, not once
  per reloader.
- Counters another plugin writes for an offline player, such as Dominion's `dominion:sieges_won` for members who
  were not on, go into that player's file instead of being dropped, and feed the server totals.
- A plugin counter raised with `raiseTo` in the first moment after a join, before the player's file was read,
  keeps the larger value instead of adding the two.
- Two plugins subscribing in the same moment, as happens when several bind together after a reload, can no longer
  leave one of them without events.
- A reload of TrackerAPI no longer stalls for 10 seconds when a plugin meets a TrackerAPI class for the first time
  while TrackerAPI shuts down, SocialBridge on the stop notice among them. The last events go out on the shutdown
  thread, which may load the class.
- `/trackerapi status` lists the plugins that use TrackerAPI with their versions, in the order a reload brings
  them back.
- The reload pass runs on its own thread, so TrackerAPI's timer never waits on another plugin's start.
- The dispatch thread skips a listener when a batch holds none of its kinds. With the 60 subscriptions of the five
  consumers of 1.0.3 a block event costs about 65 ns on that thread instead of 155, and with the 78 of all seven
  consumers about 73.

## 1.0.3

- After a reload of TrackerAPI, the plugins that use it are reloaded automatically two seconds later, so the order of reloads no longer matters.
- The default listener budget is 50 ms per batch instead of 5 ms. The warning stays, and a listener is only suspended after twenty batches in a row over budget.

## 1.0.2

- Queued crafts count one run per event. The engine fires one craft event per finished unit of a bench queue and stamps each with the quantity of the whole batch, so adding the quantity counted a batch of a hundred as ten thousand. Instant crafts still carry their real count in one event.

## 1.0.1

- Chunk unloads are read from the chunk store's lifecycle callback instead of the engine's unload event. The engine invokes that event from a parallel worker, and a system registered for it made the engine fork off the world thread and crash the world as soon as chunks unloaded with players online.
- The prefab paste hook is off for the same reason, since worldgen pastes prefabs from its generator threads. The PREFAB_PASTED kind stays in the API and is never published for now.

## 1.0.0

First release.

- One set of engine hooks for every interaction the server exposes, published as sixty two kinds of immutable event record.
- Subscriptions for other plugins, delivered off the world thread in batches with a budget per listener, or on the world thread for consumers that need the same tick.
- Lifetime counters per player with totals, per id counts and per world copies, server totals, a names index and leaderboards.
- An hourly journal per world with retention by age and size, a query API and a lookup command.
- A first boot import of the Feats and Chronicle Stats player files.
- Commands for status, hook cost with a verdict, recent events, journal lookups, counters, leaderboards, imports and config reload.
- Per world counters use a stable key per world kind, so portal and instance runs share one key such as `instance.<asset>` instead of one per run, with at most 64 keys and the rest under `other`.
- Sessions survive a plugin reload through a `session_start` field in the player file, and the longest session is raised once a minute and at shutdown, not only on disconnect.
- Breaking a block the same player placed recently, or placing again on such a spot, counts nothing, and chat counts at most once per five seconds per player, the Feats rules.
