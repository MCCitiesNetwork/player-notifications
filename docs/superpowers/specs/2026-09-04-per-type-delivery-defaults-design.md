# Per-type delivery defaults — design

**Goal:** let an operator say where a given `dataType` goes by default, without touching what any player
has chosen, and without removing the global safety net.

## Why

`settings.yml`'s `default-media` is the only operator lever, and it applies to every `dataType` at once.
A server that wants restart warnings as a Discord DM but ordinary chat notifications in chat has no way
to say so: it can move *everything* to Discord, or tell every player to configure the type themselves —
and there is deliberately no player-facing way back to the server default once they do.

Removing the global default and making the new file the only source was considered and **rejected**. The
set of `dataType`s is open-ended — the host ships three, any feature module can register more, and
`notification-types.yml` declares more still — so a per-type file can never be complete, and a type with
no entry would resolve to no media at all: stored, unread, silent, with no console line saying so.
Installing the Discord adapter or any new module would deliver nothing until someone edited a file.
`default-media` therefore stays exactly as it is, as the last step, and this file is a pure override
layer above it.

## The file

`delivery-defaults.yml`, a flat map of `dataType` → medium keys, the same shape as `type-names.yml` and
read the same way (`childrenMap()`), because the keys are the operator's own and unknown at compile
time. Ships with every example commented out, so a stock install changes nothing.

```yaml
restart-warning:
  - chat
  - discord-dm
mail:
  - discord-dm
maintenance:
  - none
```

**Its own file, not a key inside `notification-types.yml`.** That file holds only operator-*declared*
types, and the entries most worth writing are for types it does not contain — `mail` (which routes the
arrival notice), `broadcast`, `test`, and any medium-shaped type a feature module registers. Keying by
`dataType` in a file of its own covers all four sources. Cost, accepted: fully describing a declared type
is now a three-file edit (`notification-types.yml`, `categories.yml`, this one).

## Resolution

One step is inserted into `DatabaseNotificationPreferences.preferredMedia(player, dataType)`, **below
every player row and above the global default**:

| # | Source | Owner |
|---|---|---|
| 1 | rows for this player and this exact `dataType` | player |
| 2 | rows for this player under `ALL_DATA_TYPES_KEY` (`"*"`) | player, blanket |
| 3 | **this file's entry for this `dataType`** | operator, per type | 
| 4 | `settings.yml` `default-media` | operator, global |

**Below the `*` rows** so that "every player row beats every operator setting" stays true without
exception. In practice the two positions are indistinguishable — nothing in the plugin writes a `*` row;
the dialogs write exact per-`dataType` rows, and the key exists because the `api`'s older
type-agnostic `preferredMedia(UUID)` needs somewhere to store its answer. Placing the override above it
would buy nothing except a second sentence in the rule.

**`preferredMedia(UUID)` — the single-argument form — does not consult this file at all.** It resolves
under the literal `dataType` `"*"`, and a file entry keyed `*` would then be a *third* thing spelled `*`
in this codebase, with its own precedence question. The key is refused at load with a warning naming it,
and the lookup is skipped when `dataType` is `ALL_DATA_TYPES_KEY`.

**`effectiveMediaByDataType` applies the identical rule**, because it is a separate code path and it is
what the preference dialogs display for an unconfigured type. If only `preferredMedia` learned the new
step, every screen would show a default that is not the one in force.

## `none` is allowed, and makes a type opt-in

An entry of `[none]` sets the type to `NotificationPreferences.SILENCED_MEDIUM`, which is already the
stored form of a player's silence. `RenderingProcessor` drops it and returns `RETAIN`, so such a
notification is stored, readable in the inbox and counted on join, but never pushed until a player picks
media for it. This costs no new code — it is what the existing silence path already does — and it is the
only way to ship a type that is quiet until asked for.

It is **not** a mute: it is per `dataType`, and the player-level mute remains a separate switch above it.

## Types and files

| Type | Role |
|---|---|
| `paper.config.DeliveryDefaults` (new) | `load(ConfigurationNode) : Map<String, Set<String>>`. Every parsing and rejection rule, no Bukkit type, unit tested. |
| `core.DatabaseNotificationPreferences` | New step 3 in `preferredMedia` and in `effectiveMediaByDataType`; a `volatile Map<String, Set<String>> typeDefaults` alongside the existing `volatile Set<String> defaultMedia`, with `reloadTypeDefaults(Map)` mirroring `reloadDefaultMedia`. |
| `PlayerNotificationsPlugin` | Copies and loads `delivery-defaults.yml` on enable and on `/notifications reload`, pushing the parsed map into `preferences`; excludes it from `warnAboutMissingConfigKeys`. |
| `platform/paper-plugin/src/main/resources/delivery-defaults.yml` (new) | The bundled default: header comment plus commented-out examples. |

The map is parsed in `paper` and **owned in `core`** — one live copy, swapped by the same volatile
reload idiom `reloadDefaultMedia` uses, so every holder keeps its reference and `/notifications reload`
reaches all of them. No `api` change, so no separately compiled feature module breaks.

## Load rules

Nothing throws; a bad entry costs that one type and warns naming the key, the `TypeNames` operator-layer
rule. Deciding at load rather than at resolution puts the console line at the moment the edit is read,
and a reload is what reprints it.

| Case | Result |
|---|---|
| a list of one or more non-blank strings | accepted, de-duplicated into a `Set` |
| the key `*` | rejected, warned — see above |
| not a list (a scalar or a nested map) | rejected, warned |
| an empty list | rejected, warned: `[none]` is how "push nothing" is said, and an empty list is more likely a half-finished edit |
| a list with blank entries | the blanks are dropped; the entry survives if anything remains, else it is rejected and warned |

**Medium keys and type keys are not validated against any registry**, because both can name something
that registers later — a module's sink, a module's `dataType`. A medium with no registered sink is
already skipped at delivery with a `fine` log, exactly as a mistyped `default-media` entry is today.
`load` is collect-then-`putAll`+`retainAll`, never `clear()`-then-fill, so a resolution racing a reload
sees the old map or the new one, never an empty one.

## Testing

- `DeliveryDefaultsTest` (`paper-plugin`, no server) — every row of the load table above, plus the
  reload semantics: a key absent from the new file stops overriding.
- `core`'s `PlayerNotificationPreferenceTest` (real MariaDB, needs Docker) gains cases for: an override
  used when the player has no rows; an exact player row beating the override; a `*` row beating the
  override; the global default used when no override names the type; `[none]` resolving to the silence
  marker; and `effectiveMediaByDataType` agreeing with `preferredMedia` on all of it.
- The `warnAboutMissingConfigKeys` exclusion is covered by inspection, as `type-names.yml`'s is —
  `ConfigKeyGaps` is one-directional and a partial override map has no missing keys.

## Known limitations

- **No generated `defaults/delivery-defaults.yml`.** `defaults/type-names.yml` already lists every
  registered `dataType`, which is the list an operator needs in order to write this file, and a second
  generated file listing the same keys would rot in the same way for no new information.
- **No admin command to inspect the effective chain for a player.** Working out why a player is not
  receiving a type still means reading a file and a table. That gap predates this change.
- **An entry for a `dataType` nothing registers is inert and unwarned**, since a module may register it
  later. It stays in the file harmlessly, as the stale `essentials-mail` preference rows do.
- **The override does not reach a player who has already configured that type**, by construction. There
  is no way for an operator to *re-route* a type for everyone — that would mean overwriting player rows,
  which no part of this plugin does.
- **`default-media` remains reachable and still applies to every unnamed type.** This file narrows it;
  it does not replace it.
