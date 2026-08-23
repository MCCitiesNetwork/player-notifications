# Module category defaults — design

**Status:** proposed
**Date:** 2026-08-23
**Related:** `2026-08-23-config-files-are-never-rewritten-design.md`. Independent — either can ship
without the other — but they are two halves of one intent: the operator owns `categories.yml` outright,
and the plugin's only channel for telling them what a module registered is this generated file.


> **Superseded detail (2026-08-23):** the generated file is `<dataFolder>/defaults/categories.yml`,
> not `categories-defaults.yml`. Every generated file moved into a `defaults/` folder and took the
> basename of the live file it mirrors, so comparing the two is a plain `diff`. Nothing else here
> changed.

## Goal

Give a downstream consumer — a feature module, or a separate plugin registering against
`NotificationCategoryRegistry` — a way to *present* its category metadata to the server operator, and
give the operator a way to *see* it. Today a module's label, description and `dataType` claims exist
only in memory: nothing writes them anywhere an operator can read, so an operator editing
`categories.yml` is guessing at what keys and types are available to group.

The plugin writes `<dataFolder>/categories-defaults.yml`, a generated snapshot of everything the code
registry holds. The operator reconciles it into `categories.yml` by hand.

## What this is not

**The defaults file is completely isolated from live configuration.** It is written and never read.
Nothing loads it, nothing merges it, and deleting it changes no behaviour beyond removing the
operator's reference copy until the next write.

Live category resolution is **unchanged**: `NotificationCategories` is still built from
`categories.yml` merged with the code registry, config's label/description still winning a key
collision and `types` from both sources still accumulating. This design deliberately does not touch
that merge. Two alternatives were considered and rejected:

- **Reading the defaults file back as a middle layer** (`categories.yml` > defaults file > nothing).
  Rejected: the file is regenerated from the registry on every reload, so it would be a loaded copy of
  state already in memory, and anything an operator deleted from it would reappear on the next write.
  Two files loaded, one of them a cache of live state, is worse than one.
- **Making `categories.yml` entries field-wise patches** over the code registry, so an absent `label`
  means "keep the module's". Rejected as a separate concern: it changes live resolution semantics,
  which this change explicitly keeps still. It remains available later — the defaults file does not
  block it, and does not depend on it.

The accepted cost of that isolation is the classic generated-defaults rot: once an operator copies a
block into `categories.yml`, the module rewording its own label never reaches players again. That is
the price of the operator holding sole authority over what players see, which is the property being
bought. **This reasoning expires** if operators start reporting stale labels in the preference
dialogs; the fix at that point is the field-wise patch above, not a change to this file.

## Architecture

### The file

`<dataFolder>/categories-defaults.yml`, alongside `categories.yml`. It is **generated, not a bundled
resource** — `PlayerNotificationsPlugin.copyDefaultsYaml` (copy-then-merge-then-save) does not apply to
it at all, since there is no bundled default to copy and no operator edits to preserve.

Its shape is the `categories:` subtree of `categories.yml` and nothing else, so a block copied out of
it is valid where it lands:

```yaml
# GENERATED FILE - DO NOT EDIT.
# ...
categories:
  economy:
    label: "Economy"
    description: "Shop sales and payments"
    types:
      - payment
      - receipt
```

`uncategorized-label` is deliberately absent: it is a host setting, not something a module registers,
and emitting it would invite the operator to copy the whole file over `categories.yml` rather than the
one block they mean.

A category registered only via `claimDataType` — which puts `""` for both label and description — is
emitted with those empty strings rather than skipped. It is precisely the case the operator most needs
to see and name; the file header says so.

An empty registry (the stock install today — `NotificationCategoryRegistry` has no in-tree consumer)
writes an empty `categories` node.

### Determinism

Category keys and each category's `types` list are **sorted** before writing. The operator's whole
workflow is diffing this file against their `categories.yml`, and `HashMap`/`HashSet` iteration order
would churn the file on every reload, producing diffs that mean nothing. This is the one property of
the output that is load-bearing rather than cosmetic.

### Types

| Type | Module | Role |
|---|---|---|
| `core.category.CategoryDefaultsWriter` | `core` | Snapshots the registry and writes the file. Never throws. |

`CategoryDefaultsWriter`:

```java
public final class CategoryDefaultsWriter {
    public static final String FILE_NAME = "categories-defaults.yml";
    public CategoryDefaultsWriter(@NotNull Path file, @NotNull Logger logger);
    public void write(@NotNull NotificationCategoryRegistry registry);
    static @NotNull Map<String, NotificationCategoryDefinition> snapshot(
            @NotNull NotificationCategoryRegistry registry);   // package-private, for tests
}
```

`snapshot` reuses the existing `NotificationCategoryDefinition` record, so the emitted schema cannot
drift from the one `categories.yml` deserializes. It returns a `TreeMap` whose values carry sorted
`types`.

`write` renders the map through a `YamlConfigurationLoader` into a string, prepends the fixed header
comment, and writes the whole thing with `Files.writeString`. The header is prepended as text rather
than set as a Configurate node comment because YAML comment emission is a Configurate-version-dependent
capability and this needs to be certain; a plain string prefix is.

The write is **not** atomic (no temp-file-plus-move). Nothing reads the file, so a torn write costs the
operator one reload to correct, which does not justify the extra failure mode.

### Where it is called

`PlayerNotificationsPlugin` holds one `CategoryDefaultsWriter`, constructed with
`getDataFolder().toPath().resolve(CategoryDefaultsWriter.FILE_NAME)`.

The existing coalescing machinery is reused as-is — this is why the change is small. `write` is called:

1. At the end of `rebuildCategories(String)`. That covers both the post-`startModules()` rebuild in
   `onEnable` and the late-registration path, which already funnels through `scheduleCategoryRebuild`'s
   `AtomicBoolean` guard. A plugin claiming five data types therefore causes **one** file write, not
   five — the same reason that guard exists for the live rebuild.
2. In `reload(CommandSender)`, after the new snapshot is swapped in, so `/notifications reload`
   refreshes the operator's reference copy in the same breath as their edits.
3. From a **one-tick task scheduled at the end of `onEnable`**, which writes the file and nothing else —
   no live rebuild, no dialog-router swap.

It is **not** called from the initial `loadCategories()` in `onEnable`: modules have not started at
that point, so the registry is empty and the write would emit an empty file over a perfectly good one
for the few milliseconds until `startModules()` returns.

### Why the one-tick task

A task scheduled during `onEnable` runs on the server's first tick, which is after **every** plugin's
`onEnable` has returned. That makes it an unconditional backstop: whatever the registry holds once the
server is fully up is what the file says, with no dependence on how a registrant got there.

The existing change listener does not make it redundant, and the reason is in the API.
`NotificationCategoryRegistry#addChangeListener` has a **`default {}` body** — deliberately, so a
third-party registry implementation compiled against the older interface stays binary-compatible. A
host holding such a registry is therefore never notified of anything, and without this task its
defaults file would be frozen at whatever `startModules()` produced. The same applies to any registrant
mutating a registry through a path the listener does not observe.

For the ordinary case — a separate plugin registering from its own `onEnable`, which fires the listener
and schedules a rebuild that also lands on the first tick — this task simply writes the same content a
second time. That is accepted rather than guarded: the write is deterministic (see "Determinism"), so
the redundant write is byte-identical whichever of the two runs last, and a flag to suppress it would
add ordering reasoning to save one file write per server start.

The task is skipped if the plugin is no longer enabled when it fires, matching
`scheduleCategoryRebuild`'s `isEnabled()` guard: a rebuild during shutdown has nothing left to serve.

## Error handling

`write` catches `IOException` and logs at `WARNING` naming the path, then returns. A failure to write an
operator's reference copy must not fail a reload or a module registration — the same rule
`rebuildCategories` already applies to a failed re-read, and the same rule
`DefaultNotificationCategoryRegistry.fireChanged` applies to a throwing listener.

An unwritable data folder therefore degrades to "no reference copy" rather than to a broken reload.

## Testing strategy

`core`, hermetic — no Docker, no server.

`CategoryDefaultsWriterTest`:

- an empty registry writes a file whose `categories` node is empty
- a registry with two categories writes both, with label, description and types round-tripping back
  through `NotificationCategoryDefinition`
- a category registered only via `claimDataType` is present with empty label and description
- keys and `types` are emitted in sorted order, asserted against the raw file text rather than a parsed
  map, since ordering is the point
- writing twice over the same path with the same registry produces byte-identical content
- the header is present and the file's first line is a comment
- an unwritable path logs and does not throw

The one line each in `rebuildCategories` and `reload` is **not** covered — both need a live server.
Manual verification is in the plan's final task.

## Known limitations

- **The file rots once copied.** Named above under "What this is not"; deliberate, and the condition
  for revisiting it is recorded there.
- **No in-tree consumer exercises it.** `NotificationCategoryRegistry` still has no in-tree registrant,
  so on a stock install this file is empty forever and the interesting paths run only in unit tests.
  Moving `diagnostics` from `categories.yml` into code was considered as a live consumer and rejected
  for this change: it would alter what a stock install's preference dialogs are built from, which is
  exactly the live behaviour this design keeps still.
- **No reconciliation aid beyond the file.** There is no command that diffs the defaults against
  `categories.yml`, and no warning when a module registers a category the operator has never mentioned.
  Both are cheap to add later on top of `snapshot`, and neither is needed to make the file useful.
- **Only categories.** Nothing here lets a module present a default *medium* set for its data types;
  that fallback chain (`dataType` rows, then `*` rows, then `settings.yml`'s `default-media`) is
  untouched and remains host-owned.
