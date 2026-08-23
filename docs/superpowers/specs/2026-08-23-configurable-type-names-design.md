# Configurable notification type names — design

**Status:** proposed
**Date:** 2026-08-23
**Related:** `2026-08-23-module-category-defaults-design.md` — this reuses its generated-dump pattern.

## Goal

A `dataType` is a registry key chosen by a module author (`mail`, `broadcast`, `essentials-mail`).
Today both preference surfaces title-case it, which is a guess at a human name: nobody downstream can
say what their type is really called, and no operator can overrule it.

Two halves:

1. **A module can supply a default display name** for the types it registers, alongside the payload
   mapping it already registers.
2. **The operator overrides any of them** in `<dataFolder>/type-names.yml`, and sees what modules
   supplied in a generated `<dataFolder>/type-names-defaults.yml`.

## Resolution chain

`TypeNames.name(dataType)` resolves, in order:

| Source | Wins over | Format |
|---|---|---|
| `type-names.yml` entry | everything | MiniMessage |
| module default, via `NotificationDataTypeRegistry` | the fallback | MiniMessage |
| title-cased registry key | — | plain |

The operator's file always wins, which is the one property the whole change exists to deliver. The
chain is consulted **live on every call** — there is no frozen snapshot, so unlike the category
registry this needs no change listener and no rebuild; a module registering late is simply picked up
by the next render.

## Decisions

**The module default goes on `NotificationDataTypeRegistry`, not a new registry.** That class already
keys on `dataType` and already owns everything else about one — payload class, processor, serializer,
renderer. A display name is one more fact about the same key, so this extends the existing axis rather
than adding a fourth. It is a concrete class with no implementors, so new methods are source- and
binary-compatible for the modules compiled against it.

```java
public void registerDisplayName(@NotNull String dataType, @NotNull String miniMessage);
public void unregisterDisplayName(@NotNull String dataType);
public @NotNull Optional<String> displayName(@NotNull String dataType);
```

**An entry replaces the type name only, not the whole row.** The in-game medium editor renders a row
as `primaryCategory.label + ": " + typeName` (`PreferenceDialogs.dataTypeLabel`); an override changes
only the second half, so `mail: "Personal Mail"` reads `Mail: Personal Mail`. Replacing the whole row
was rejected — a file with some types overridden and some not would show two row shapes in one list,
and the category prefix is what makes that flat checkbox list read as grouped. Discord's select has no
category prefix, so there the name *is* the whole visible label; that difference is in the surfaces,
not in the file.

**Values are MiniMessage** at both layers, consistent with `messages.yml`. Dialog labels are already
`Component`s. Discord select options are plain strings, so the adapter flattens with
`PlainTextComponentSerializer` — the same trade `NotificationSink#displayName` already makes there.

**A missing, blank or malformed value falls through to the next layer.** Malformed matters:
MiniMessage throws on a legacy `§` code and both layers are hand-written, so a bad tag must degrade
rather than break the preference screen. A malformed *operator* value falls through to the module
default, not straight to the key, so a typo costs the override and nothing else. This mirrors
`MessageContainer.messageFor` rendering a broken template as its key rather than throwing.

**A malformed operator value is warned about, at load and not at render.** `name` is called every time
a preference screen opens, so warning there would reprint the same line for the life of the server and
bury it. Instead `load` parse-checks every value it reads, logs one `WARNING` naming the key and the
parse error, and **drops the entry** — so the fall-through is decided once, the console line appears at
the moment the operator's edit is read, and `/notifications reload` is what reprints it after they try
a fix. Silence here was the wrong default: an operator who mistypes a tag would otherwise see their
rename simply not happen, with nothing anywhere saying why.

A malformed *module* default is a code bug in someone else's jar rather than an operator's typo, and it
cannot be validated at load because a module may register after the file is read. It is caught at
render and warned **once per `dataType`**, guarded by a set that `load` clears, so a reload re-reports
whatever is still broken.

**Not part of `messages.yml`.** `MessageKeysTest` walks `MessageKeys` by reflection in both directions
against the shipped file, so a key nothing reads fails it. Keys here are per-`dataType` and unknown at
compile time — every one would fail that direction. A standalone file has no such contract.

**Excluded from `warnAboutMissingConfigKeys`.** That check reports keys the bundle has and the
operator's lacks; this file is inherently a partial map of overrides and ships with every example
commented out, so it has no keys to miss. Listing it would warn on every startup for the normal case.

**The shipped `type-names.yml` contains no active entries.** A shipped `mail: "Mail"` would be an
override the operator never asked for, and would freeze that label against a module later supplying a
better default — the same rot `categories-defaults.yml` records. Examples are comments.

### `type-names-defaults.yml`

Generated, written on the same triggers as `categories-defaults.yml`, and **never read** — the pattern
that design established, for the same reason: the operator reconciles by hand and their file stays
theirs.

**It lists every registered `dataType`, not only those with a module default.** This is a deliberate
divergence from the category dump, which lists only what code registered. The file's job is answering
"what can I rename, and what does it say now", and a registry-only dump would omit exactly the types
most in need of renaming — the ones with an ugly key and no module default. Each entry carries its
effective default, and a comment marks which came from a module and which is the title-cased fallback,
so the operator can tell a considered name from a guess.

Keys sorted, deterministic output, plain-text header prepended — all as `CategoryDefaultsWriter`
already does, and for the same diff-stability reason.

## Architecture

`paper.localisation.TypeNames`, beside `MessageKeys`. A text concern used by two surfaces, not
preference logic, which is why it is not in `paper.preferences`.

```java
public final class TypeNames {
    public TypeNames(@NotNull NotificationDataTypeRegistry registry, @NotNull Logger logger);
    public void load(@NotNull ConfigurationNode root);          // replaces the override map, in place
    public @NotNull Component name(@NotNull String dataType);
    public @NotNull String plainName(@NotNull String dataType);
    public @NotNull Optional<String> override(@NotNull String dataType);   // for the dump's comments
    public static @NotNull String titleCase(@NotNull String key);
}
```

**Final field, reloaded in place, never replaced** — the idiom `MessageContainer` uses here, for the
same reason: every holder takes the reference at construction, so `/notifications reload` reaches all
of them with no re-registration. Backed by a `ConcurrentHashMap`; a read racing a reload is a stale
read at worst. A blank value is dropped at load, and so is an unparseable one (warned, above), so both
are decided once rather than per render — which is also what keeps `name` free of a try/catch for the
operator layer.

`titleCase` moves here from `PreferenceDialogs`, which becomes a caller. That helper's javadoc already
records it as a deliberate third copy of the one on `NotificationSink`/`AccountLinkProvider`; this
keeps `paper`'s copy at one rather than adding a fourth.

### Shared generated-file writing

`CategoryDefaultsWriter` and the new type-name dump differ only in their header, their root key and
their value type. The duplication is worth removing now that there are two: `core.config.GeneratedYaml`
gains

```java
public static void write(@NotNull Path file, @NotNull String header, @NotNull String rootKey,
                         @NotNull TypeToken<T> type, @NotNull T content, @NotNull Logger logger);
```

carrying the never-throws contract, the plain-text header prefix and the non-atomic write, each of
which `CategoryDefaultsWriter`'s javadoc already justifies. `CategoryDefaultsWriter` delegates to it
and keeps its own public shape, so its seven existing tests are the safety net for that extraction —
they must stay green untouched.

### Call sites

| Where | Change |
|---|---|
| `api.NotificationDataTypeRegistry` | the three display-name methods |
| `paper.localisation.TypeNames` | new |
| `paper.localisation.TypeNameDefaultsWriter` | new; renders the dump |
| `PreferenceDialogs.dataTypeLabel` | takes `TypeNames`; `Component.text(categoryLabel + ": ").append(typeNames.name(dataType))` |
| `MediumEditorDialog` | passes `router.typeNames()` |
| `PreferenceDialogRouter` | holds `TypeNames`, exposes `typeNames()` |
| `PlayerNotificationsPlugin` | owns `TypeNames` and the writer; loads beside `reloadMessages()`, reloads in `reload`, writes the dump wherever `categoryDefaultsWriter.write` is already called; exposes `typeNames()` |
| `discord.command.PreferenceView` | constructor gains `Function<String, String> typeLabel`, replacing its private static `label`; `DiscordModule` wires `plugin.typeNames()::plainName` |

`PreferenceView` takes a `Function` rather than `TypeNames` so it stays unit-testable without a host
plugin — the seam `TestNotificationRenderer` and `MailRecipients` already use.

## Error handling

`load` never throws: a non-scalar node is skipped, matching `MessageContainer.load`'s rule that a
structurally odd node costs one wrong label, not a failed reload. A value that will not parse is
logged at `WARNING` naming the key and dropped, so one bad entry never costs the rest of the file.

`name` never throws: a `ParsingException` from the module layer falls through to the title-cased key
and is warned once per `dataType`. The operator layer cannot throw here, because `load` already
rejected anything unparseable.

The dump writer never throws, inheriting `GeneratedYaml`'s contract.

## Testing strategy

Hermetic throughout; no Docker, no server.

`api` — `NotificationDataTypeRegistryTest` (extend): a registered display name is returned; an
unregistered one is empty; unregister removes it; re-registering overwrites.

`platform:paper-plugin` — `TypeNamesTest`:

- an unconfigured type with no module default renders as its title-cased key, `-` and `_` as separators
- a module default is used when the operator has no entry
- an operator entry beats a module default
- MiniMessage formatting survives in `name`, and `plainName` flattens it
- a malformed operator value (a legacy `§`) falls through to the module default, not to the key
- a malformed operator value logs one warning naming the key, and is dropped rather than re-parsed on
  every `name` call (assert the entry is gone and that a second `name` call logs nothing further)
- a malformed module default falls through to the title-cased key and warns once per `dataType`, not
  once per call
- `load` clears the warned-once set, so a reload re-reports a module default that is still broken
- a blank value at either layer falls through, silently — a blank is a deletion, not a mistake
- `load` replaces the map: a key removed from the node stops overriding
- a non-scalar node value is skipped rather than throwing

`TypeNameDefaultsWriterTest`: every registered type appears even with no module default; entries are
sorted; module-supplied and fallback entries are distinguishable in the output; two writes are
byte-identical; an unwritable path logs and does not throw.

`PreferenceDialogsTest`: `dataTypeLabel` keeps the category prefix and uses the override for the
second half only.

`platform:discord-adapter` — `PreferenceViewTest` (extend): the injected label function supplies the
type select's option labels.

`CategoryDefaultsWriterTest` must pass **unchanged** after the `GeneratedYaml` extraction.

The dialogs, the router wiring and `DiscordModule` need a live server; manual checklist in the plan.

## Known limitations

- **Sort order keys off the raw `dataType`, not the display name.**
  `PreferenceDialogs.sortedDataTypes` sorts by category label then registry key, so renaming `mail` to
  `Zebra Post` leaves it sorted under `m`. Sorting by a value an operator can colour needs a decision
  about what tags do to collation; deferred until enough types are renamed for it to show.
- **Nothing validates that a key in `type-names.yml` names a registered `dataType`.** A typo is
  silently inert. `NotificationCategories.typesWithNoPayloadMapping` already does this shape of check
  for `categories.yml` and the same could be wired here cheaply; left out to keep this to one concern.
- **No in-tree module supplies a default name yet**, so like the category registry the module-default
  layer is exercised only by unit tests. The host's own three types (`mail`, `broadcast`, `test`) are
  candidates but registering them would make the shipped dump non-empty, which is a separate call.
- **No per-player locale**, matching `messages.yml`. One file, one language.
- **The category half of the row is not configurable here** — that is `categories.yml`'s `label`.
