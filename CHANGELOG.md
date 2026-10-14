# Changelog

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
