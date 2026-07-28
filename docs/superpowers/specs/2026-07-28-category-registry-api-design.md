# Notification category registry API

Date: 2026-07-28
Status: approved, not yet implemented

## Problem

Categories currently exist only as a config-driven, exclusive `dataType -> categoryKey` map built by
`core.category.NotificationCategories` from `categories.yml`, and preferences are stored and resolved
per **category**. Two problems follow from that:

1. Module authors (e.g. `platform:essentials-adapter`) have no way to declare a category or claim one of
   their data types for it in code — every category has to be hand-added to `categories.yml` by the
   server operator, and every `dataType` must live under exactly one category (first-claim-wins).
2. Because preferences are stored per category, a `dataType` cannot meaningfully belong to more than one
   category — the moment it did, delivery-time preference lookup would have to pick a category to resolve
   against, which is arbitrary.

This design does two things together, because the second is what unblocks the first:

- **Preference storage and resolution move from per-category to per-`dataType`.** A category becomes a
  pure display/grouping concept over data types, used only by the preference dialogs (for both listing
  and bulk "toggle everything in this category" actions) — never a delivery-time lookup key.
- **Categories become registrable in code**, in a new `api.category` package, the same way payload types,
  processors, renderers and sinks already are. `categories.yml` keeps working exactly as before as one
  source of category claims; module registrations are just more claims layered on top. Because
  categories no longer gate delivery, a `dataType` can now belong to any number of categories with no
  collision to resolve.

## Decisions and rejected alternatives

| Decision | Chosen | Rejected |
|---|---|---|
| Preference storage/resolution axis | The registry **`dataType`** directly | Category (today's model) — blocks a `dataType` from belonging to more than one category |
| `dataType` -> category relationship | **Many-to-many** (a `dataType` may be claimed by any number of categories) | Exclusive one-to-one with first-claim-wins (today's model) |
| Category definition source | **`categories.yml` and code, merged** | Config-only (today's model); code-only |
| Category's role at delivery time | **None** — `NotificationDelivery` never resolves or needs a category | Category still resolved and passed to `RenderingProcessor` (today's model) |
| Category's role in the dialogs | Grouping for display, and a bulk-edit convenience that expands to per-`dataType` writes | A separate storage/lookup axis (today's model) |
| Registering a category in code | A registry method (`registerCategory`/`claimDataType`) on a new `api.category.NotificationCategoryRegistry`, mirroring `NotificationDataTypeRegistry` | Ad hoc annotations on payload classes; a builder passed at `NotificationService` construction time |

## Data model

### Schema

`PlayerNotificationPreference` moves from `(playerUuid, category, medium)` to
`(playerUuid, dataType, medium)`. The project is still in prototyping with no data to preserve across
schema versions — the same reasoning that collapsed earlier migrations into `V1__maria_initial_schema.sql`
applies here, so this is edited in place in `V1__maria_initial_schema.sql` rather than layered as a new
migration step:

```sql
CREATE TABLE PlayerNotificationPreference (
    playerUuid BINARY(16)  NOT NULL,
    dataType   VARCHAR(64) NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, dataType, medium)
);
```

`PlayerNotificationPreferenceEntity.category()` renames to `dataType()`; the mapper's
`selectByPlayerAndCategory`/`deleteByPlayerAndCategory` rename to `selectByPlayerAndDataType`/
`deleteByPlayerAndDataType`.

### Lookup precedence

Unchanged in shape, just re-keyed. For a given `(player, dataType)`:

1. Rows matching the exact `dataType`.
2. Failing that, rows for the reserved `*` key (`DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY`,
   renamed from `ALL_CATEGORIES_KEY`) — a blanket choice.
3. Failing that, `default-media` from `settings.yml`.

A `dataType` whose only row is `medium = 'none'` is an explicit mute, exactly as today. The three-state
model (unconfigured / explicit / muted) is unchanged, just resolved per `dataType` instead of per
category.

### Reserved keys

| Key | Meaning |
|---|---|
| `*` | `dataType` fallback; never shown in the UI, never written by the dialogs directly. |
| `none` | Not a category or `dataType` — the `NullSink` medium, denoting an explicit mute. |
| `uncategorized` | Still a real, always-selectable category (see below) for display purposes — holds every `dataType` no other category claims. |

## Category registry API

### `api.category` (new package)

```java
public interface NotificationCategoryRegistry {
    void registerCategory(@NotNull String categoryKey, @NotNull String label, @NotNull String description);
    void claimDataType(@NotNull String categoryKey, @NotNull String dataType);
    void unclaimDataType(@NotNull String categoryKey, @NotNull String dataType);
    @NotNull Set<String> categoryKeys();
    @NotNull Set<String> dataTypesFor(@NotNull String categoryKey);
}
```

A default in-memory implementation (`api.category.DefaultNotificationCategoryRegistry` or similar) backs
it; `claimDataType` for an unregistered `categoryKey` registers it with an empty label/description rather
than throwing, mirroring how `registerPayloadMapping` has no precondition on prior state. Module authors
reach it the same way they reach `dataTypeRegistry()` today — a `categoryRegistry()` accessor alongside it
(exact host wiring is an implementation detail resolved during planning, e.g. on `NotificationService` or
`PlayerNotificationsPlugin`).

### `core.category.NotificationCategories` (existing type, repurposed)

Becomes a **read-side merge** of `categories.yml` and the programmatic `NotificationCategoryRegistry`,
built once at startup (and on `/notifications reload`, alongside its existing config reload):

- `resolve(dataType)` changes signature from returning one `String` to `@NotNull Set<String> resolve(String dataType)` — every category (config + code) that claims it. An unclaimed `dataType` resolves to `Set.of(UNCATEGORIZED)`.
- `categoryKeys()`, `label(key)`, `description(key)` unchanged in shape; a code-registered category's label/description come from `registerCategory`, a config one from `categories.yml`; if both claim the same key, config wins (config is operator-controlled and reviewed, the more trustworthy source for player-facing text — code registration of an already-config-defined key is an edge case, not the common path).
- No collision warning is needed for `dataType` claims anymore, since membership is a set, not an exclusive slot. A collision on the *category key itself* (code and config both defining `economy` with different labels) still logs at `fine`, noting config's label won.
- `typesWithNoPayloadMapping(registry)` keeps working unchanged (still checking claimed data types against `NotificationDataTypeRegistry`).

## Delivery simplifies

`NotificationDelivery` no longer needs `NotificationCategories` at all: `dispatch` calls
`preferences.preferredMedia(target, notification.notifPayloadType())` directly. The 6-arg,
category-resolving constructor is removed; the 5-arg rendering constructor (database, registry, sink
registry, preferences, logger) becomes the "full" form. `RenderingProcessor` drops its category
constructor argument and calls the `dataType`-keyed lookup directly. Everything else about dispatch
precedence, the DELETE-wins fold, and partial-delivery logging is unchanged.

`NotificationPreferences`'s two-argument method keeps its signature shape but changes meaning:

```java
@NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType);
```

This is a breaking change to a still-young API with no external consumers, so no compatibility shim.

## Preference dialogs

`PreferenceEditSession`'s matrix becomes `Map<String dataType, Set<String> medium>` (renamed from
category-keyed), with the same dirty-set/reset-set/Apply staging semantics as today, just re-keyed.
`DatabaseNotificationPreferences.effectiveMediaByCategory`/`explicitlyConfiguredCategories` rename to
`effectiveMediaByDataType`/`explicitlyConfiguredDataTypes` and take the full set of known data types
(from `NotificationDataTypeRegistry`, not from `NotificationCategories`, since an uncategorized
`dataType` still needs a session entry).

- **`MediumEditorDialog`** ("by delivery method"): still one screen per medium, but its checkbox list is
  now one row per `dataType`, with categories used only as section headers/grouping for readability (a
  `dataType` claimed by multiple categories appears once, e.g. under whichever category the UI treats as
  primary for grouping purposes — display detail, resolved during planning).
- **`CategoryPickerDialog` -> `CategoryEditorDialog`** ("by notification type"): picking a category shows
  one checkbox per medium **for that category as a whole**. Checking/unchecking a medium there fans out
  to `toggleCategoryMedium`-equivalent writes on every `dataType` the category claims — i.e. "mass toggle
  is individually toggling each data type" per your instruction. If the category's member data types
  currently have divergent per-medium state (because one was edited individually via the medium editor,
  or the category has data types added after some were configured), the editor shows a "mixed" indicator
  for that medium rather than asserting a single checked/unchecked state; interacting with it still
  applies uniformly to every member from that point on.
- **"Use server default"** for a category now means: reset every `dataType` the category claims.
- **`/notifications mute`** and the root screen's "mute everything" no longer iterate `categoryKeys()` —
  they iterate every `dataType` known to `NotificationDataTypeRegistry`, so an uncategorized `dataType`
  is still covered by a bulk mute. Same for `/notifications reset`.

## Testing

`:core:test` (Testcontainers, needs Docker):

- Preference precedence re-keyed to `dataType`: exact rows, `*` fallback, `default-media`.
- `NotificationCategories.resolve` returns the union of config- and code-claimed categories for a
  `dataType`; an unclaimed `dataType` resolves to `{uncategorized}`.
- A `dataType` claimed by two categories (one config, one code) resolves to both.
- `applyChanges`/`muteAll`/`resetAll` operate correctly re-keyed to `dataType`.

`:api:test`:

- `NotificationCategoryRegistry`: register, claim/unclaim, multiple categories claiming the same
  `dataType`.
- `RenderingProcessor` calls the `dataType`-keyed `preferredMedia` overload directly, with no category
  involved.

The five preference dialogs remain unverified by automated tests, unchanged from today — verify by hand
with `:platform:paper-plugin:runServer`.

## Known limitations

- **`dataType`s claimed by no category and no config entry are only reachable via `uncategorized`** in
  the "by notification type" pivot; this was already true today, unchanged.
- **Rows for a `dataType` whose payload mapping/category claims are later removed are kept, not pruned**
  — same pre-existing behavior as rows for a removed category today, just re-scoped to `dataType`.
- **The "mixed state" UX for a category whose members have diverged is a display nuance**, not a data
  integrity issue — the underlying per-`dataType` rows are always the source of truth.
- **Partial delivery under DELETE-wins fan-out is still silent** — unrelated to this change, carried over
  from the renderer design.
- **Explicit `NotificationProcessor`s still bypass preferences entirely** (and therefore categories),
  same pre-existing quirk.
