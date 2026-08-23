# Config files are never rewritten — design

**Status:** proposed
**Date:** 2026-08-23
**Related:** `2026-08-23-module-category-defaults-design.md` (independent; either can ship without the other)

## Goal

The plugin must never write to a config file the operator owns. It copies a bundled default in when the
file is absent, and from then on the file on disk is theirs alone.

## The bug

`PlayerNotificationsPlugin.copyDefaultsYaml` (`:588`) does, on **every** call:

```java
ConfigurationNode existing = loader.load();
existing.mergeFrom(defaultsLoader.load());
loader.save(existing);        // <- rewrites the operator's file
```

`categories.yml` is therefore rewritten at startup, on every `/notifications reload`, **and** on every
late-registration category rebuild — `scheduleCategoryRebuild`'s own javadoc admits the last of these
("each rebuild re-reads and re-writes `categories.yml`"). `database.yml`, `settings.yml` and
`messages.yml` are rewritten at startup and on reload.

Each rewrite is a Configurate re-emit, so it strips the operator's comments, reorders keys to the node
tree's order, and normalises quoting and block style. `messages.yml` is the worst case — it is the file
an operator is most expected to hand-edit and annotate — but every one of the four is affected.

## Design

### `categories.yml`, `settings.yml`, `database.yml`: copy once, then read

`copyDefaultsYaml` keeps only its first half. If the file is absent, the bundled resource is copied in.
Then it is loaded and returned. No `mergeFrom`, no `save`.

The file on disk becomes the sole and complete truth for these three. That is what makes deletion work:
an operator removing a category from `categories.yml` currently finds it merged straight back in on the
next load, and after this it stays gone.

### `messages.yml`: bundled defaults under the operator's file, in memory

`messages.yml` is the exception, because a *missing* key there is not a neutral absence.
`MessageContainer.messageFor` renders an unknown key as `Component.text(key)`, so a message key added in
a later release would print `mail.sent` to players on every existing install, and `MessageKeysTest`
cannot catch it — it checks the shipped resource, not the operator's file.

So `reloadMessages` loads the bundled resource and the operator's file and merges the former **under**
the latter before handing one node to `MessageContainer.load`:

```java
ConfigurationNode operatorNode = copyDefaultsYaml("messages");   // no merge, no save
ConfigurationNode merged = bundledNode("messages");              // fresh node from the jar
merged.mergeFrom(operatorNode);                                  // operator's values win
this.messages.load(merged);
```

Direction matters and is easy to get backwards: `a.mergeFrom(b)` fills keys absent from `a` out of `b`,
so the node that must *win* is the one `mergeFrom` is called on. `MessageContainer`'s own javadoc names
this arrangement ("If you need a defaults-plus-overrides arrangement, merge the two ConfigurationNode
trees and load the result once"), which is the intended use.

Nothing is written to disk. The operator's file holds overrides; a key they delete falls back to the
shipped wording rather than printing its own name.

The asymmetry with the other three is deliberate and worth stating plainly: in `messages.yml` an absent
key means "I did not override this", because every key has a shipped default that is a sensible value.
In `categories.yml` an absent category means "I do not want this category", because a category is a
whole object the operator composes rather than a slot with a natural default.

### The gap warning replaces what the merge used to do silently

Dropping the merge from the three read-only files loses a real guarantee: today an upgrade adding a key
gets that key into every operator's file automatically. Without it,

- an added **primitive** key deserializes to `0`/`false`. `PluginSettings`' compact constructor already
  absorbs that for `prune-interval-seconds` and `inbox-page-size`, but a future `boolean` defaulting to
  true would silently arrive as `false`;
- an added **`@Required` reference** key deserializes to `null` and Configurate throws, so
  `loadPluginSettings` fails and `onEnable` disables the plugin. `PluginSettings.defaultMedia` and all
  three `DatabaseSettings` fields are already of this shape.

So `onEnable` gains one check per read-only file: load the bundled resource, walk its keys, and log a
single `WARNING` naming every key present in the bundle and absent from the operator's file, telling
them to add it. The operator gets the same information the merge used to deliver, and their file still
is not touched.

This is not belt-and-braces — it is the load-bearing half of the change. Without it, "copy once" turns
every future config addition into a silent wrong default or a startup failure with no diagnostic
pointing at the cause.

The check is comparison-only and never mutates either node. It runs at enable and on
`/notifications reload`, not on a category rebuild, since a rebuild cannot change what is on disk.

`PluginSettings`' comment on `deliver-on-join` — "Both keys are written into every data folder by the
copy-defaults-then-merge path, including on upgrade" — becomes false and is corrected in the same
change to point at the gap warning instead.

## Types and files

| File | Change |
|---|---|
| `paper/PlayerNotificationsPlugin.java` | `copyDefaultsYaml` loses `mergeFrom`/`save`; new `bundledNode(String)`; new `warnAboutMissingConfigKeys()`; `reloadMessages` does the under-merge |
| `paper/config/ConfigKeyGaps.java` (new) | `static List<String> missingKeys(ConfigurationNode bundled, ConfigurationNode actual)` — the flatten-and-diff, with no Bukkit dependency so it is unit-testable |
| `paper/PluginSettings.java` | corrected comment |

`ConfigKeyGaps.missingKeys` flattens both trees to dotted paths (the same shape `MessageContainer`
flattens to) and returns the sorted bundled-minus-actual difference. A key present with a different
*value* is not a gap — that is an override, which is the whole point.

## Error handling

A missing bundled resource is already logged at `SEVERE` by the copy path and is unchanged. The gap
check swallows nothing: it can only fail if the bundled resource will not parse, which is a packaging
bug and is logged at `WARNING` rather than failing enable, since the operator's own file has already
loaded fine by then.

## Testing strategy

`platform:paper-plugin`, hermetic.

`ConfigKeyGapsTest`:

- identical trees produce no gaps
- a key in the bundle and absent from the operator's file is reported by dotted path
- a nested key (`categories.mail.label`) is reported by its full dotted path
- a key present with a different value is **not** reported
- a key the operator added that the bundle lacks is not reported (the diff is one-directional)
- gaps are returned sorted, so the warning is stable across runs
- an empty operator node reports every bundled key

`copyDefaultsYaml`, `reloadMessages` and the warning call sites need a live server and a data folder;
they are covered by the plan's manual checklist.

## Known limitations

- **An operator who deletes a `@Required` key breaks their own startup**, with the gap warning as the
  only clue — and the warning is logged after the failure that disabled the plugin in the
  `database.yml` case, since that loads first. Making the check run before every load was considered
  and rejected as reordering `onEnable` for a case an operator creates deliberately.
- **The gap warning cannot tell an operator's deliberate deletion from an upgrade's new key.** A
  `categories.yml` with a category removed on purpose warns about it on every startup. Accepted:
  suppressing it would need a record of what the operator has acknowledged, which is a state file, which
  is the thing this change exists to stop writing.
- **Existing installs are not repaired.** Whatever the merge already wrote to their files stays; this
  change only stops adding to it.
- **`messages.yml` is the only file with a defaults-underlay.** If `settings.yml` ever grows a key
  whose absence is genuinely harmless to default in memory, the same treatment would suit it, but doing
  it now would give three files two different rules for no present benefit.
