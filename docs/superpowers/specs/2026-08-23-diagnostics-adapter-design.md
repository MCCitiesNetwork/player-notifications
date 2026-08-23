# Diagnostics adapter — design

**Status:** proposed, not yet built
**Module:** `platform:diagnostics-adapter` (new)
**Related:** `2026-07-29-test-notification-design.md` (the feature being moved, unchanged in substance),
`2026-08-20-essentials-mail-converter-design.md` (the module-owned-command precedent this follows),
`2026-07-28-category-registry-api-design.md` (the category registry this finally gives a consumer)

## Goal

Move the test/diagnostics feature — the `test` data type, its payload, renderer and sender, and the
command that drives it — out of `platform:paper-plugin` and into a feature module of its own.

The behaviour a player sees is the same diagnostic it always was: enqueue a notification targeting
yourself, deliver it immediately through the real path, and report which media were attempted. What
changes is **who owns it**. Today the host carries a payload class, a renderer, a registry
registration, a Brigadier subcommand, a permission, seven `messages.yml` keys and a shipped category
for a feature that exists only to prove the plugin works. None of that is part of what
PlayerNotifications *does*; all of it is deletable on a production server that has finished
commissioning.

The move is also the first end-to-end exercise of two extension points `CLAUDE.md` currently records
as having no in-tree consumer: `NotificationCategoryRegistry`, and the `defaults/categories.yml`
dump that renders it.

## The shape of the change

A new feature module, a jar in `<dataFolder>/modules/` like the other two. Package
`io.github.md5sha256.playernotifications.diagnostics`. It owns:

- the `test` payload, its renderer and its `registerJsonRenderable` registration,
- a code-registry category claim for `test`,
- one command, `/pntest [message]`, and one permission,
- no schema, no config file, no sink, no processor, no `messages.yml` participation.

It registers along the existing axes only. `registerJsonRenderable` on the payload-type registry and
`claimDataType`/`registerCategory` on the category registry are exactly the two calls a third-party
module would make; this module is the in-tree proof that they are sufficient.

### Why a module

The host's dependency on the diagnostics is one-directional and shallow: three classes, one
registration and a command node, none of which anything else in the host reads. That is the
definition of a feature module here. It also means an operator who does not want an op-only
"send yourself a notification" command on their server deletes a jar rather than living with it.

The counter-argument — that a diagnostic which proves the plugin works should not itself be
optional — cuts the other way in practice: a diagnostic that ships inside the thing it tests cannot
prove that module loading, cross-classloader payload registration and late category registration
work, because it never crosses those boundaries. Running it from a module exercises strictly more of
the system than running it from the host did.

### Why a module-owned root command, and not `/notifications test`

Paper's Brigadier tree is built when `LifecycleEvents.COMMANDS` fires, and modules start inside
`onEnable` before that — but the host's `notifications` node is *built in one call* by
`NotificationsCommand.create`, which takes its collaborators as constructor arguments. A module
cannot add a child to a node it does not build, and Paper exposes no mutation of an already
registered node (the same fact that forced `<provider>` to be an argument rather than one literal
per provider under `link`).

The two ways to keep `/notifications test` were both rejected:

- **A host-side lookup seam** — keep the node, resolve the implementation from the module at call
  time, reply "diagnostics are not available on this server" when absent. This is the shape the
  Discord `link` subtree uses, and it is the right shape *there* because linking is a generic
  capability with a registry (`AccountLinkRegistry`) that more than one provider could fill.
  Inventing a registry for a single hardcoded diagnostic would leave the host carrying the seam, the
  permission, the messages and the "not available" branch — most of what this change exists to
  remove — in exchange for keeping a command name.
- **Leaving it in the host.** Then nothing moves.

So the module registers its own root, `/pntest [message]`, through `LifecycleEvents.COMMANDS` from
`initialize`. This is the `essentials-mail-converter` precedent, and it is safe here for the same
reason: the module is `reloadable: false` and never unregisters, so `shutdown` is empty and no
surgery through the live command map is needed. **`/notifications test` ceases to exist.**

### Late registration is correct, and this is why

The host's current comment — "register the built-in test payload before `registerCommands()`: the
preference dialogs enumerate `dataTypes()`, so `test` must already be mapped" — describes an
ordering constraint that does not actually exist. It goes away with the registration.

`PreferenceDialogRouter` is *constructed* during `registerCommands()`, but every screen reads
`dataTypeRegistry().dataTypes()` and `sinkRegistry().registeredMedia()` **live, when the dialog
opens**. That is what makes `discord-dm` appear in the media list at all, since the Discord adapter
registers its sink during `startModules()` — after `registerCommands()`, exactly where this module
will register its payload. `onEnable` likewise already rebuilds `NotificationCategories` after
`startModules()` and swaps it in through `reloadCategories`, the two-pass ordering that exists
precisely so a module's late category claims are picked up.

Consequence, stated plainly: a server without the diagnostics jar has no `test` row in its
preference dialogs and no Diagnostics category. That is correct — a preference row for a
notification type nothing can produce is noise.

## Types and files

### Created — `platform/diagnostics-adapter/`

| File | Holds |
|---|---|
| `build.gradle.kts` | `paper-adapter` convention plus `testRuntimeOnly` on `paper-api`. No shading: the module bundles no library. |
| `src/main/resources/module-manifest.yml` | `module-name: diagnostics-adapter`, `entry-class`, `expected-plugin-class`, `reloadable: false`. Kebab-case keys. |
| `…/diagnostics/DiagnosticsModule.java` | `PluginModule<PlayerNotificationsPlugin>`. Registers the renderable, claims the category, registers the command. Empty `shutdown`. |
| `…/diagnostics/TestNotificationPayload.java` | Moved verbatim from `paper.diagnostic`, package line changed. |
| `…/diagnostics/TestNotificationRenderer.java` | Moved. Body text loses "`/notifications test`" in favour of "`/pntest`". |
| `…/diagnostics/TestNotificationSender.java` | Moved. `MessageContainer` dependency replaced by hardcoded components (below); `Supplier<NotificationDelivery>` retained. |
| `…/diagnostics/TestNotificationCommand.java` | The `/pntest` Brigadier node. Wiring only, mirroring `ConvertMailCommand`'s shape. |
| `src/test/…/TestNotificationRendererTest.java` | Moved. |
| `src/test/…/TestNotificationSenderTest.java` | Moved; fixture drops `TestMessages`. |

### Modified — host

| File | Change |
|---|---|
| `settings.gradle.kts` | `include("platform:diagnostics-adapter")` |
| `platform/paper-plugin/build.gradle.kts` | one `featureModules(project(path = ":platform:diagnostics-adapter", configuration = "moduleJar"))` line |
| `PlayerNotificationsPlugin.java` | delete the three `paper.diagnostic` imports, the `registerJsonRenderable` block and its comment, and the `TestNotificationSender` construction; drop the `testSender` argument at the `NotificationsCommand.create` call site |
| `command/NotificationsCommand.java` | delete the `test` literal subtree, `TEST_PERMISSION`, `DEFAULT_TEST_MESSAGE`, the `TestNotificationSender` import and parameter |
| `localisation/MessageKeys.java` | delete the seven `TEST_*` constants |
| `resources/messages.yml` | delete the `test:` block |
| `resources/paper-plugin.yml` | delete the `playernotifications.command.test` permission |
| `resources/categories.yml` | delete the `diagnostics` category block |

### Deleted — host

`platform/paper-plugin/src/main/java/…/paper/diagnostic/` and
`platform/paper-plugin/src/test/java/…/paper/diagnostic/`, both entirely.

## Decisions

### Messages are hardcoded in the module

`messages.yml` is a host file with a host contract: `MessageKeysTest` walks `MessageKeys` against the
shipped resource **in both directions**, so a key nothing reads fails just as a constant pointing at
nothing does. A module cannot add constants to `MessageKeys` and cannot add lines to the host's
bundled resource, so a module's strings cannot live there without inventing a per-module message
file. `CLAUDE.md` already fixes the rule for exactly this reason — "Deliberately still hardcoded:
… Both feature modules."

So the seven `test.*` keys and the seven `MessageKeys.TEST_*` constants are deleted **together**, in
one commit, and `TestNotificationSender` builds the same five replies as `Component`s directly. The
wording is carried across unchanged apart from the command names it quotes.

**This is a loss of configurability, and it is deliberate.** The alternative — a
`diagnostics-messages.yml` — is a config file, a loader, a defaults-underlay and a reload path for
five strings in an op-only diagnostic. That is the same trade the Discord adapter took for its far
larger string surface. If a per-module message file is ever wanted, it is wanted for the Discord
adapter first, and this module should join that design rather than pre-empt it.

The two replies that quote host commands (`/notifications unmute`, `/notifications preferences`)
keep quoting them: the mute and the per-type silence really do live on the host's tree, and this
module has no say over them.

### The category moves into the code registry, not into the module's config

`categories.yml` ships a `diagnostics` category claiming `test`. Deleting that block without
replacement would leave `test` claimed by nothing, resolving to `UNCATEGORIZED` — a Diagnostics
notification filed under "Other". So the module claims it in code:

```java
NotificationCategoryRegistry registry = service.categoryRegistry();
registry.registerCategory("diagnostics", "Diagnostics", "Test notifications sent with /pntest");
registry.claimDataType("diagnostics", TestNotificationPayload.TEST_DATA_TYPE);
```

Both signatures are as `NotificationCategoryRegistry` declares them today, and
`NotificationService#categoryRegistry()` is the accessor the module resolves them through.

This is the right half of the many-to-many merge for a module-supplied type. `categories.yml` is the
operator's authority and a module has no business writing to it; the code registry is the half that
exists for exactly this. An operator who wants a different label defines `diagnostics` in their own
`categories.yml`, where config wins the merge — and `defaults/categories.yml` now finally *shows*
them the block to copy, having dumped an empty `categories` node on every stock install until now.

**Upgrade note, accepted:** an existing install's `categories.yml` already contains the `diagnostics`
block, because it was copied on first run and config files are never rewritten. It will keep winning
the merge, with a description still naming `/notifications test`. Harmless — the label and the type
claim are identical — and the operator can delete the block to pick up the module's version.
Deleting it for them would mean rewriting their file, which is the thing
`2026-08-23-config-files-are-never-rewritten-design.md` exists to stop.

### The permission is declared nowhere

`playernotifications.diagnostics.test`, named for the module rather than for a host command that no
longer exists. `paper-plugin.yml` belongs to the host and programmatic registration is the pattern
the Discord adapter was cleaned of, so the node is undeclared and resolves through Bukkit's default —
op only. That is the same gate `playernotifications.command.test` had (`default: op`) and the same
treatment `essentialsmailconverter.command.convert` gets.

An operator who wants to grant it to a rank defines it in their permissions plugin. This is a real
downgrade in discoverability versus a declared node, and it is the established cost of a
module-owned command here.

### Player-only, and off the main thread

`/pntest` targets the sender's own inbox and reads the sender's own preferences, so a console sender
has nothing to send to; it is player-only, unlike `/essmailconvert`. `TestNotificationSender.send`
keeps its existing `runTaskAsynchronously` body unchanged — mappers and preference lookups do
blocking JDBC and `DiscordDmSink` refuses the main thread outright.

### What does *not* change

- `TestNotificationSender`'s core decision — enqueue through `NotificationService` and then call
  `NotificationDelivery.deliver(UUID)`, rather than invoking a sink directly, so the diagnostic
  exercises serialization, the due query, dispatch precedence, rendering, preference resolution,
  fan-out and pruning. `2026-07-29-test-notification-design.md` remains the authority on why.
- The `Supplier<NotificationDelivery>` indirection. `reload()` replaces the object, and
  `plugin::notificationDelivery` is the reload-safe form of the same seam the host used.
- The four-way `report` split (muted / silenced / no media / delivered) and the "no registered sink"
  addendum, all of which stay behind the package-private `report(UUID)` seam that makes them
  testable without a live `Player`.
- The renderer's `Function<UUID, String>` name-lookup seam and `usingServerNames()`.
- The `test` data type string itself. Renaming it would orphan every stored
  `PlayerNotificationPreference` row naming it — the `essentials-mail` inertness already recorded in
  "Current state" is what that looks like.
- The host's `notificationDelivery()`, `preferences()`, `sinkRegistry()` and `notificationService()`
  accessors. All four already exist and are already public for modules; **no host API is added.**

## Error handling

Unchanged in substance, relocated:

- A `RuntimeException` from the enqueue-or-deliver body is logged at `WARNING` against the target and
  reported to the player as a hardcoded red line naming the exception message. The exception text is
  inserted as a literal `Component`, never parsed as MiniMessage — the reason the host code used
  `MessageContainer.value` rather than `markup`.
- `initialize` throws nothing new. Registration against the two registries cannot fail for a
  first-and-only claimant; a duplicate `test` mapping would mean two diagnostics jars installed, and
  the registry's own behaviour governs that.
- No `LinkageError` guard, unlike the EssentialsX converter: this module names no third-party type
  and depends on no other plugin.

## Testing strategy

**Moved, and expected to pass unchanged in substance:**

- `TestNotificationRendererTest` (6 tests) — moves verbatim but for the package line and the body
  text now naming `/pntest`.
- `TestNotificationSenderTest` (5 tests) — the fixture drops `TestMessages` and the
  `MessageContainer` constructor argument. Every assertion is already a plain-text `contains` on the
  rendered reply, so the four-outcome coverage survives the hardcoding intact; the two assertions
  naming `/notifications unmute` and `/notifications preferences` stay as they are, because those
  commands stay as they are.

**Host suites that must be re-run because this change touches their subject:**

- `MessageKeysTest` — the direct check that the seven constants and the seven YAML keys were removed
  together. It fails loudly if either half is left behind, which is the whole reason it exists.
- `ConfigKeyGapsTest`, `PreferenceDialogsTest` — neither is expected to change; run them because
  `categories.yml` and the registered data-type set both move underneath them.

**Not tested, by construction:** `DiagnosticsModule.initialize`, the `/pntest` Brigadier node, and
`TestNotificationSender.send`. All three need a live server. This is the same boundary every module
entry class and command node in the tree sits behind, and it is why the decision-holding half of the
sender is a package-private `report(UUID)`.

**Manual verification** (needs `:platform:paper-plugin:runServer`, and is the first end-to-end run
of the category registry through a real module class loader):

1. `./gradlew :platform:paper-plugin:runServer` — the log names `diagnostics-adapter` at startup.
2. `/pntest` as an op — a test notification arrives through the player's preferred media.
3. `/pntest hello there` — the custom message appears, bold, in the body.
4. `/notifications test` — no such subcommand; it is absent from tab completion.
5. As a non-op, `/pntest` is absent from tab completion and refused.
6. `/notifications preferences types` — a **Diagnostics** category exists, containing one type.
7. `<dataFolder>/defaults/categories.yml` contains the `diagnostics` block with its module-supplied
   label and description — the first non-empty `categories` node a stock install has ever written.
8. `/notifications mute` then `/pntest` — the muted reply, naming `/notifications unmute`; the
   notification is still in `/notifications`.
9. Silence `test` in the preference dialog, then `/pntest` — the silenced reply, worded apart from
   the no-media one.
10. Remove the jar from `modules/`, restart — the plugin enables cleanly, `/pntest` is gone, and no
    Diagnostics category or `test` row appears in the preference dialogs.

## Known limitations

- **`/notifications test` is removed with no alias and no deprecation period.** Anything scripted
  against it breaks. Judged acceptable: it is an op-only interactive diagnostic, not an interface.
- **The five diagnostic strings become unconfigurable**, as argued above. The route back is a
  per-module message file, which should be designed for the Discord adapter first.
- **The permission is undeclared**, so it does not appear in `/help`, in permission dumps, or in a
  plugin manager's permission list. Same cost the EssentialsX converter already pays.
- **An existing install keeps its stale `diagnostics` description** naming `/notifications test`,
  because config files are never rewritten. Cosmetic, and self-correcting for anyone who deletes the
  block.
- **`TypeNames` still gets no module-supplied display name.** The module could call
  `registerDisplayName("test", …)` and would be the first in-tree consumer of that layer too, but
  `TypeNames.titleCase("test")` already yields "Test" — registering a name that reproduces the
  fallback exercises the code path at the cost of a claim that means nothing. That branch stays
  unexercised outside unit tests; naming it is a separate, cheap change if wanted.
- **No second diagnostic lands here.** A "which sinks are registered" or "what are my effective
  preferences" command would be a natural neighbour, and this module is the obvious home, but
  nothing is designed and nothing is scaffolded for one. `/pntest` is a root literal with no
  subcommands, so adding one later is a non-breaking change to this module alone.
- **The module still cannot be reloaded.** `reloadable: false`, like both existing modules, because
  a module-owned Bukkit command cannot be unregistered without command-map surgery.
