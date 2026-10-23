# Changelog

## 1.3.0

Three more mods and one more generic kind. Updating is a jar swap.

- Bindings for Angler's Almanac (fish caught by species, rarity and grade, legendary and golden catches, best grade,
  performance, perfect streak and size, species, Almanac pages and zones found, casts, hooks, escapes), Arcanum
  (spells cast by name, energy spent and the most expensive, most complex and largest spell, failed casts, runes
  known) and Ymmersive Melodies (songs started, finished and stopped, time played by instrument, the longest song,
  songs played together and the largest band, distinct songs and instruments). Fourteen mods in all.
- `ModTally(mod, stat, id, amount, best)`, the 77th kind, for counts and bests the other mod kinds do not cover.
  The sink adds to `<mod>:<stat>` and `<mod>:<stat>:<id>` and raises `<mod>:<stat>_best`.
- The plural of a discovery total keeps "species" as it is.

## 1.2.1

Three small fixes to the mod bindings. Updating is a jar swap.

- The parry streak best and the Endgame wave best are raised before the event goes out, so a plugin that reads
  the counter on the event (Feats does) sees the new value at once instead of one event late.
- The Endgame combo personal best follows the mod's combo tier events while playing. Before, it moved only at a
  join and at the bind.

## 1.2.0

TrackerAPI reads the popular mods. Updating is a jar swap, the API grew and nothing changed for the plugins on it.

- Eleven mod bindings: RPG Leveling, Major Dungeons, Perfect Parries, Wan's Wonder Weapons, Zephyr, Simple
  Enchantments, Vein Mining, Lucky Mining, MMO Skill Tree, Endgame and QoL and Alec's Tamework. Each is found by
  plugin id every five seconds, bound through the mod's own classes while it is enabled, and let go when it is
  missing, reloaded or set to `never` under `mods` in `TrackerAPI.json`. A mod whose API changed in part keeps the
  features that still match. The README lists what each one counts.
- Fourteen new event kinds for what the mods do, 76 in all, each record with the binding id in its `mod` field:
  `ModLevelUp`, `ModXpGained` (high volume), `ModChoiceChanged`, `ModAbilityUsed`, `ModAction`, `ModMilestone`,
  `DungeonRun`, `ModBossDefeated`, `ItemEnchanted`, `EnchantmentTriggered` (high volume), `VeinMined`, `LuckyDrop`,
  `CompanionChanged` and `ModBound`.
- Counters under each binding's prefix, such as `rpg:level`, `dungeons:fastest:<dungeon>` or `parries:streak_best`,
  with leaderboards for the totals and bests. Bests merge as bests, firsts as firsts, after a join as before.
  Mod XP under a point is carried per player and source until it adds up to one, so small awards count. Veins
  and lucky drops carry the `creative` flag of their break and follow `countCreative` like block breaks.
- `TrackerApi.mods()` (API 2) lists every mod TrackerAPI knows with its bound state, version, the feature groups
  that resolved or did not, and every counter it writes with a label, so a stats plugin shows them without a table
  of its own. `ModBound` fires on every bind and unbind. The player snapshot carries `facts()` such as `rpg.level`
  and `rpg.class`.
- `/trackerapi mods` prints the state of every binding with its features and counters, and `/trackerapi status`
  gained a Mods line. New settings `mods.pollSeconds` and `mods.<id>`.

## 1.1.2

Moving items out of a chest and back in no longer counts as finding them. Updating is a jar swap.

- Swapping two items between a chest and the inventory, by dropping a stack on a slot that holds a different
  item, counted the item from the chest as gained every time and never noted the one that went in. Done back and
  forth, it raised `items_gained` and the `obtained` feats of Feats without end. Every move to or from another
  container is now read on the player's side, what left and what arrived, so a swap is a put away and a take at
  once. Quick stack, take all and put all go the same way.
- Items put into a chest or dropped were remembered for 10 minutes and only until a restart, so waiting or
  restarting made the same items count again. TrackerAPI now keeps a balance per player and item of what was put
  away and not taken back, with no time limit. It is saved in the player's file as `stash:<item id>`, a reserved
  prefix that never shows as a stat, on a board, in the server totals or in an API read.
- Items taken out of a container block a player placed never count as gained. The engine marks every placed
  block with who placed it and keeps that with the world, so chests placed before TrackerAPI was installed count
  as placed too. The output slots of furnaces and other processing benches still count, as their items are new.
  World generated chests count what is not on the balance.
- Moves to and from containers in Creative now add to and take from the balance, so items put into a chest in
  Creative do not count when taken out later in Adventure. Creative gains still never count.
- `ItemsGained` is unchanged. `quantity` is still the full amount moved and `source` is still CONTAINER, only
  `fresh` changed. Plugins need no rebuild.
- Totals that earlier versions already raised this way stay as they are. Nothing is taken back.

## 1.1.1

Planting and harvesting crops count now. Updating is a jar swap.

- Planting a seed was counted under the seed bag's item id with no block, and replanting a harvested plot was
  thrown away as a repeat placement, so farms showed next to nothing. The engine plants through the same
  `PlaceBlockEvent` as any block, but with the seed bag in hand and no word of the crop. TrackerAPI now reads the
  crop from the seed's place interaction, so `BlockPlaced.blockId` is `Plant_Crop_Carrot_Block` for
  `Plant_Seeds_Carrot`, and the new `planted` flag is true for any block that grows in stages, crops and saplings.
- A planting is only a repeat when the player broke an unripe crop on that spot since planting it, since that
  hands the seed back. Replanting after a harvest counts every time.
- New counters `crops_planted` and `crops_planted:<crop block>`, and `crops_harvested` and
  `crops_harvested:<crop block>` for a ripe crop broken by hand. The harvest counts on the player's own plot,
  where `block_broken` still does not. `BlockBroken` has a new `ripeCrop` flag for it.
- Harvests with a sickle and picking a berry bush fire no block event in 0.7.1, so they are not in
  `crops_harvested`. Their drops still count as pickups.

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
