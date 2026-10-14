# Changelog

## 1.0.0

First release.

- One set of engine hooks for every interaction the server exposes, published as sixty two kinds of immutable event record.
- Subscriptions for other plugins, delivered off the world thread in batches with a budget per listener, or on the world thread for consumers that need the same tick.
- Lifetime counters per player with totals, per id counts and per world copies, server totals, a names index and leaderboards.
- An hourly journal per world with retention by age and size, a query API and a lookup command.
- A first boot import of the Feats and Chronicle Stats player files.
- Commands for status, hook cost with a verdict, recent events, journal lookups, counters, leaderboards, imports and config reload.
