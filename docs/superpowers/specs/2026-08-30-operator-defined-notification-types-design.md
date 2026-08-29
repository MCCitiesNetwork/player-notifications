# Operator-defined notification types

**Date:** 2026-08-30
**Status:** approved, not yet implemented
**Plan:** `docs/superpowers/plans/2026-08-30-operator-defined-notification-types.md`

## Goal

Let a server operator declare a named, rich-text notification type in configuration — without writing
a plugin — and send one with `/broadcast <content> --type <key>`. A declared type is a **real
registered `dataType`**: it enumerates in `dataTypes()`, so a player can silence it or route it to
Discord independently of ordinary broadcasts, it renders in the inbox, and a `--persistent` one is
pushed on the recipient's next join like any other notification.

Today the only way to add a `dataType` is to write and ship a feature module. Every operator-facing
knob that *names* a type already exists (`type-names.yml` renames one, `categories.yml` groups one),
but there is no way to bring one into existence.

## Architecture

### The declaration

A new `notification-types.yml`, a flat map at the root — the shape `type-names.yml` uses, because the
keys are unknown at compile time and so cannot be a `@ConfigSerializable` record:

```yaml
restart-warning:
  title: "<red><bold>Server Restart"
  display-name: "<red>Restart Warnings"
```

- **`title`** is required, MiniMessage, and is the notification's title on every medium.
- **`display-name`** is optional, MiniMessage, and is the type's name in the preference dialogs. It is
  registered through `NotificationDataTypeRegistry#registerDisplayName`, i.e. **layer 2** of
  `TypeNames` — so `type-names.yml` still overrides it, and this feature becomes the first in-tree
  consumer of a registry layer that until now only unit tests exercised.
- **No `category` key.** Category membership stays solely `categories.yml`'s, exactly as it is for a
  module's type; a second source of claims would need a merge rule and a collision rule for no new
  capability. An unclaimed declared type resolves to `UNCATEGORIZED`, which is already selectable.
- **No body template and no placeholders.** The body is whatever the sender types, as it is for
  `/broadcast` today. Config fixes the identity and the styling of the title; the message is the
  sender's.

### The payload and the renderer

`NotificationDataTypeRegistry` keys renderers and serializers by **payload class** and mappings by
`dataType`. There can be no class per declared key without codegen, so every declared type shares one:

```java
public record CustomNotificationPayload(@NotNull String typeKey, @NotNull String message) {}
```

**The payload carries its own `typeKey`**, and that is load-bearing rather than redundant: N data
types map to one class, one class maps to one renderer, so the shared `CustomTypeRenderer` has no
other way to know which title to use. Reading the title out of `CustomNotificationTypes` at render
time (not at enqueue time) also means an operator editing a title and reloading changes how *already
stored* notifications read — which is the behaviour an operator will expect from a file they think of
as templates.

`CustomTypeRenderer` parses `message` as MiniMessage inside a `try`, falling back to literal text,
for the reason `MailRenderer` and `BroadcastRenderer` do: MiniMessage *throws* on a legacy `§` code,
and an unrenderable payload must not take down the inbox screen. A payload whose `typeKey` is no
longer declared renders with the title-cased key as its title rather than failing.

**No processor is registered**, deliberately — an explicitly registered processor wins dispatch and
would bypass preferences and sinks entirely. That is right for `mail` and wrong here: a declared type
exists precisely so a player can choose where it reaches them.

### Registration and reload

`CustomTypeRegistrar` owns the registry side. `sync()` diffs the currently declared keys against the
set it registered last time:

- new keys: `service.registerJsonRenderable(key, CustomNotificationPayload.class, renderer)` plus
  `registerDisplayName` when one is declared;
- removed keys: `registry.unregisterPayloadMapping(key)` and `unregisterDisplayName(key)`.

**Removal cannot go through `unregisterDataType`**, which also unregisters the payload class's
processor, serializer and renderer — and that class is shared, so removing one declared type would
break every other one. This needs one additive method on `NotificationDataTypeRegistry`:

```java
public void unregisterPayloadMapping(@NotNull String dataType)
```

Additive and on a concrete class with no implementors, so no feature module compiled against the old
`api` breaks.

`sync()` is called at the end of `onEnable` (after `startModules()`, so a module's claim on a key is
already visible) and again from `/notifications reload`.

**Stored notifications of a deleted type keep their rows.** With the mapping gone,
`InboxEntryRenderer`'s existing "unrenderable payload" placeholder names the data type, and re-adding
the key to the file restores them. Hiding the entry would leave it counted in `totalEntries`, and
retaining the registration for a key the operator deleted would leave a phantom row in the preference
dialogs with no way to remove it.

### Rejected declarations

Checked at load, each warned once naming the key, and dropped:

| Rule | Why |
|---|---|
| blank or absent `title` | there is nothing to render; a title is required on every medium |
| `title` or `display-name` is not valid MiniMessage | the `TypeNames` operator-layer rule: decide it at load so the console line appears when the edit is read, not on every screen open |
| key longer than 64 characters | `PlayerNotificationPreference.dataType` is `VARCHAR(64)`; a longer key would store a notification whose preference row cannot exist |
| key not `[a-z0-9._-]+` | a `dataType` is a registry key shared with module authors and a Discord select option; a key with spaces or markup would render as itself in three surfaces |

A bad `display-name` drops **only the name**, not the type: the type still renders, and `TypeNames`
falls through to the title-cased key. A bad `title` drops the whole declaration, because there is no
third layer to fall through to.

Checked at `sync()`, warned and skipped: a key **already mapped by the host or a module** (`mail`,
`broadcast`, `test`, a module's). Re-registering `mail` against `CustomNotificationPayload` would
replace the payload mapping the mail feature depends on and break `/mail` silently.

### `/broadcast --type <key>`

One new value-taking flag. `BroadcastArguments` gains a `@Nullable String type` component and `--type`
to its token set; last occurrence wins, consistent with `--chain` and `--limit`. Because an
unrecognised token is *rejected* rather than absorbed into the content, adding a flag can only turn
text that was already a hard error into a flag — the property that made the previous four flags a pure
addition.

**The key's validity is not checked in `BroadcastArguments`.** That class holds no registry and its
tests need no server; `BroadcastCommand` validates against the live declared set and replies
`broadcast.unknown-type`, naming the key.

The flag then swaps two things through the whole pipeline:

- the **`dataType`** preferences resolve against — which is the entire point, since silencing
  "restart warnings" must not silence broadcasts;
- the **title** the notification renders with.

`Broadcaster.broadcast`, `Broadcaster.suppressed` and `PersistentBroadcaster.broadcast` currently
hardcode `Broadcaster.BROADCAST_DATA_TYPE` and `MessageKeys.BROADCAST_TITLE`. Each gains those as
parameters. `suppressed` must take the `dataType` too, or `--bypass` on a typed broadcast would ask
who was suppressed for the wrong type.

`PersistentBroadcaster` stores `new CustomNotificationPayload(key, rawContent)` under the declared
`dataType` when the flag is present, and the existing `BroadcastPayload` under `broadcast` when it is
not — so nothing about an untyped broadcast changes on disk or in behaviour.

## Files

**Create**
- `platform/paper-plugin/src/main/resources/notification-types.yml` — the bundled default, every
  example commented out, as `type-names.yml` ships.
- `paper/customtype/CustomNotificationTypes.java` — load, validate, hold; reloaded in place.
- `paper/customtype/DeclaredNotificationType.java` — `record (String key, String title, @Nullable String displayName)`.
- `paper/customtype/CustomNotificationPayload.java`
- `paper/customtype/CustomTypeRenderer.java`
- `paper/customtype/CustomTypeRegistrar.java`
- Tests: `CustomNotificationTypesTest`, `CustomTypeRendererTest`, `CustomTypeRegistrarTest`.

**Modify**
- `api/…/NotificationDataTypeRegistry.java` — `unregisterPayloadMapping(String)`.
- `paper/broadcast/BroadcastArguments.java` — the `--type` flag and the `type` component.
- `paper/broadcast/Broadcaster.java` — `dataType` and title parameters on `broadcast` and `suppressed`.
- `paper/broadcast/PersistentBroadcaster.java` — `dataType` and payload parameters.
- `paper/command/BroadcastCommand.java` — validation and wiring.
- `PlayerNotificationsPlugin.java` — load, `sync()` at enable and on reload, a `customTypes()`
  accessor for modules.
- `messages.yml` + `MessageKeys` — `broadcast.unknown-type`.
- `CLAUDE.md`.

## Error handling

Nothing in the load path throws: a malformed entry costs that one type, never the file and never
startup — the trade `TypeNames.load` and `MessageContainer.load` already make. A `sync()` collision is
a warning and a skip, never an exception, because it can be caused by installing an unrelated module.
`/broadcast --type` with an undeclared key is a command rejection before anything is resolved or
enqueued, so it costs no queries.

## Testing strategy

Unit, no Docker and no server — every decision is in a class holding no Bukkit type, the split
`/mail` and `/broadcast` already use:

- `CustomNotificationTypesTest` — a good declaration; an absent title; a blank title; a bad-MiniMessage
  title; a bad-MiniMessage `display-name` (the type survives, the name is dropped); an over-64-character
  key; an illegal-character key; a scalar where a map was expected; that a reload drops a deleted key.
- `CustomTypeRendererTest` — title from config; body parsed as MiniMessage; a `§` body falling back to
  literal; an undeclared `typeKey` falling back to the title-cased key; `target` ignored.
- `CustomTypeRegistrarTest` — first sync registers; a second with a key removed unregisters exactly
  that mapping and leaves the shared renderer intact; a key already mapped elsewhere is skipped and
  not unregistered by a later sync.
- `BroadcastArgumentsTest` — `--type` parsed; missing value; last-occurrence-wins; a `--type` token
  ending the content.
- `BroadcasterTest` / `PersistentBroadcasterTest` — that a non-default `dataType` is what preferences
  are resolved against, and that the declared title reaches the sink.

**Needs a live server, so a manual checklist instead** (the plan's final task): the file being copied
on first enable, a declared type appearing in `/notifications preferences`, `/broadcast … --type`
delivering with the declared title, silencing the type without silencing `broadcast`, a `--persistent`
typed broadcast reading correctly in the inbox and after a title edit plus reload, and a deleted type
showing the placeholder.

## Known limitations

- **No body template and no placeholders.** A declared type styles a title; it does not template a
  message. Adding placeholders later means a payload field (`Map<String, String>`) and a substitution
  pass — additive, but it needs a serializer tolerant of the field's absence in already-stored rows.
- **`/broadcast` is the only sender.** Nothing else in-tree enqueues a declared type, and there is no
  scheduling, so a "restart warning" still has to be typed by an operator or driven by another plugin
  through `NotificationService`.
- **A declared type cannot claim a category from its own file**, so defining one that groups nicely is
  a two-file edit. Deliberate, per the single-owner rule above; revisit if operators report the
  two-file dance as real friction, not on principle.
- **`--type` cannot appear in a broadcast's text**, the standing cost of the first-flag-token parsing
  rule, now paid a fifth time.
- **Titles are shared across every notification of a type.** Two announcements needing different
  titles need two declared types.
- **No admin surface for orphaned rows.** Stored notifications of a deleted type sit in inboxes as
  placeholders until dismissed or the key is re-added; nothing prunes them, matching the existing
  behaviour for stale `essentials-mail` preference rows.
