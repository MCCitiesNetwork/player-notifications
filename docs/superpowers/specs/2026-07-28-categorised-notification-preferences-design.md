# Categorised notification preferences

Date: 2026-07-28
Status: approved, not yet implemented

## Problem

A player's notification preference is currently a flat, set-valued choice of delivery media:
`PlayerNotificationPreference(playerUuid, medium)`, edited by a single dialog with one checkbox per
registered medium. It answers "where do I want notifications", but not "which notifications do I want
where". A player who wants shop sales in Discord but moderation notices only in chat has no way to say
so.

This design replaces the flat set with a **category x medium matrix**, edited through two dialog flows
that pivot over the same state:

- **By delivery medium** — pick a medium, check which notification categories reach you there
  ("uncheck five kinds of notification from Discord in one go").
- **By notification category** — pick a category, check which media it reaches you through
  ("send Economy notifications via chat, Discord and Essentials mail").

Neither flow is primary. Both edit one state, so they cannot disagree.

## Decisions and rejected alternatives

| Decision | Chosen | Rejected |
|---|---|---|
| Preference axis | A **category**, coarser than a payload type | The registry `dataType` directly (too many rows in the UI as payload types multiply) |
| Where categories are defined | **`categories.yml`**, owned by the server operator | Declared in code at payload registration; code-with-config-override |
| What a preference row stores | The **category key** | The `dataType` (a checkbox would then write N rows and lie about what it wrote) |
| Instance-level keying (`notifKey`) | **Rejected** | `notifKey` identifies one enqueued row, is caller-supplied and unique per notification; preferences are configured before the notification exists |
| Storage shape | **Row per enabled cell** | Global baseline + per-category overrides (two tables, ambiguous by-medium semantics); explicit tri-state column (checkboxes cannot express a third state) |
| Commit model | **Staged in a session, explicit Apply** | Immediate write per editor screen |
| Inherited (unconfigured) state | **Show effective state, write only dirty categories** | Silent promotion of everything touched; materialising defaults as real rows on first open |

## Data model

### Schema — `V3__categorised_preferences.sql`

Replaces the V2 table:

```sql
CREATE TABLE PlayerNotificationPreference_v3 (
    playerUuid BINARY(16)  NOT NULL,
    category   VARCHAR(64) NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, category, medium)
);

INSERT INTO PlayerNotificationPreference_v3 (playerUuid, category, medium)
SELECT playerUuid, '*', medium FROM PlayerNotificationPreference;

DROP TABLE PlayerNotificationPreference;
RENAME TABLE PlayerNotificationPreference_v3 TO PlayerNotificationPreference;
```

Existing rows migrate to the reserved category `*`, meaning "applies to any category not otherwise
configured". Migration therefore needs no knowledge of `categories.yml`, which is not read until after
the migrator runs, and no existing player's choice is lost.

Adding the migration means **both** the `V3__categorised_preferences.sql` file and a `MigrationStep`
entry in `MariaSchemaMigrator.DEFAULT_MIGRATIONS` — that list is hardcoded. Each statement must survive
the migrator's naive split on `;`.

### Lookup precedence

For a given `(player, category)`:

1. Rows matching the exact `category` — the player configured this category explicitly.
2. Failing that, rows for the reserved `*` category — a pre-migration preference, or a blanket choice.
3. Failing that, `default-media` from `settings.yml`.

A category whose only row is `medium = 'none'` is an **explicit mute** for that category; `NullSink`
reports `DELIVERED`, so those notifications are consumed rather than accumulating until expiry. This is
the same three-state model as today (unconfigured / explicit / muted), now resolved per category
instead of per player.

### Reserved keys

| Key | Meaning |
|---|---|
| `*` | Category fallback; never shown in the UI, never written by the dialogs. Only the V3 migration creates it. |
| `uncategorized` | A real, selectable category holding every `dataType` no configured category claims. |
| `none` | Not a category — the `NullSink` medium, denoting an explicit mute. |

## Category resolution

A new `NotificationCategories` type in `core` holds the `dataType -> categoryKey` map plus each
category's label and description, deserialized from `categories.yml` with Configurate into
`@ConfigSerializable` records. Every non-null reference-typed `@Setting` field carries `@Required`, per
the project convention.

```yaml
uncategorized-label: "Other"
categories:
  economy:
    label: "Economy"
    description: "Shop sales, payments, balance changes"
    types: [mail, shop-sale]
  moderation:
    label: "Moderation"
    description: "Warnings and staff messages"
    types: [warning]
```

- A `dataType` claimed by no category resolves to `uncategorized`, which is selectable in both dialogs.
  A newly installed module's notifications are therefore configurable immediately, without the operator
  editing config first.
- A category listing a `dataType` nothing has registered is logged at startup and still shown; config
  may legitimately anticipate a module that is not installed yet.
- A `dataType` listed by two categories is a config error: the first wins, and a warning names both.
- A malformed `categories.yml` aborts plugin enable, consistent with `database.yml` and `settings.yml`.

## API changes

- `NotificationPreferences` gains `@NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String category)`.
  The existing single-argument method remains for callers with no category context and resolves against
  `*` then `default-media`.
- `RenderingProcessor` takes the category key as a constructor argument and calls the two-argument
  lookup.
- `NotificationProcessor`, `NotificationRenderer`, `NotificationSink`, `RenderableNotification` and
  `DeliveryResult` are **unchanged**. Adding a medium or a payload type still costs one registration.

`DatabaseNotificationPreferences` implements the new lookup and gains
`setPreferredMedia(UUID, Map<String, Set<String>>)`, which writes several categories in one
transaction, and `preferredMediaByCategory(UUID)` for loading a whole matrix into an edit session.

## Dispatch

`NotificationDelivery.dispatch` already has `notification.notifPayloadType()` in hand when it constructs
the per-notification `RenderingProcessor`. It resolves that to a category through
`NotificationCategories` and passes the key in. Nothing downstream changes: renderers, sinks, the
DELETE-wins fold and the partial-delivery logging are untouched.

The existing precedence quirk stands: an explicitly registered `NotificationProcessor` (such as
`EssentialsMailProcessor`) wins over the rendering path, so it bypasses preferences and therefore
bypasses categories too. Pre-existing; fixing it means converting those processors into sinks, which is
out of scope here.

## User interface

The current `NotificationPreferencesDialog` is replaced by five screens in `paper.preferences`, sharing
a `PreferenceDialogs` helper for medium/category labels and `ClickCallback.Options`:

| Screen | Contents |
|---|---|
| `PreferenceRootDialog` | "By delivery method", "By notification type", "Mute everything", "Reset all to server default", "Apply" (shown only when the session is dirty, labelled with the change count), "Discard" |
| `MediumPickerDialog` | One button per registered medium except `none` |
| `MediumEditorDialog` | One checkbox per category — which notifications reach the player on this medium |
| `CategoryPickerDialog` | One button per configured category plus `uncategorized` |
| `CategoryEditorDialog` | One checkbox per medium except `none`, plus "use server default for this category" |

Both editors mutate the same in-memory matrix, so the two pivots always agree.

Conventions carried over from the current dialog: input keys are **positional** (`medium_0`,
`category_0`, ...) with a key-to-value map, because medium and category keys are arbitrary strings and
any sanitising transform risks two of them colliding onto one input; button callbacks use
`ClickCallback.Options` with `uses(1)` and a one-hour lifetime; blocking JDBC runs on the async
scheduler and `Player#showDialog` back on the main thread.

Checkbox state shows the **effective** media for a category — inherited defaults look identical to
explicit choices. The pickers mark unconfigured categories (e.g. `Economy (server default)`) so the
distinction is visible where it matters.

## Edit session

`PreferenceEditSession` (per player) holds:

- the loaded `Map<String category, Set<String> medium>` matrix,
- the set of categories the player has changed (**dirty set**),
- a last-touched instant.

`PreferenceSessionManager` keys sessions by UUID, expires them after **15 minutes idle**, and drops a
player's session on quit. Dropping on quit requires registering the plugin's **first `Listener`**
(`PlayerQuitEvent`).

Semantics:

- An editor's Save writes **only into the session** and marks that category dirty.
- **Apply** writes each dirty category wholesale in one transaction, then clears the session. Untouched
  categories are never written, so a concurrent change to them is not clobbered.
- A category left with **nothing** checked is a mute, stored as a single `none` row. This holds however
  the set was emptied: unchecking a category's last remaining medium in the *medium* editor mutes that
  category exactly as unchecking every box in the *category* editor does. Emptying never falls back to
  the server default — only the explicit "use server default" action does that.
- "Use server default for this category" deletes that category's rows.
- **Discard** drops the session without writing.
- If Apply fails, the session and its dirty flags are **kept** and the player is told to retry. Because
  the write is one transaction, a half-written matrix is not possible.
- Reopening after expiry starts fresh from the database; staged changes are lost, and the root screen
  says so when it finds no session.
- Media are re-validated against `NotificationSinkRegistry#registeredMedia()` at Apply, so a medium
  unregistered while the session was open is dropped rather than persisted as an unroutable preference.

## Commands

All under the existing `playernotifications.command.preferences` permission, player-only:

| Command | Effect |
|---|---|
| `/notifications`, `/notifications preferences` | Root screen |
| `/notifications media` | Straight to the medium picker |
| `/notifications types` | Straight to the category picker |
| `/notifications mute` | Writes a mute for every category **immediately** |
| `/notifications reset` | Clears all of the player's rows **immediately** |

`mute` and `reset` are the one deliberate asymmetry: they write immediately and discard any open
session with a chat notice. A fire-and-forget command that silently required a follow-up Apply would be
a trap. The equivalent root-screen buttons stage like everything else.

## Testing

`:core:test` (Testcontainers, `mariadb:11.7`, needs a running Docker daemon):

- V3 migration preserves pre-existing rows as category `*`.
- Lookup precedence: exact category, then `*`, then `default-media`.
- Per-category writes affect only the given categories.
- A mute stores exactly one `none` row; "use server default" deletes the category's rows.
- Multi-category write is atomic.

`:api:test`:

- `RenderingProcessor` passes its category through to a fake `NotificationPreferences`.
- `NotificationCategories` resolution: configured type, unclaimed type to `uncategorized`, duplicate
  claim resolution, unknown type.

Count results by globbing `*.xml`, not `TEST-*.xml` — Gradle shortens `@Nested` result filenames on
Windows. Current baseline is 50 tests in `:core:test` and 12 in `:api:test`.

The five dialogs remain **unverified by automated tests**, the same gap as the dialog they replace.
Verify by hand with `./gradlew :platform:paper-plugin:runServer`.

## Known limitations

- **Partial delivery is still silent.** Unchanged from the renderer design: if one preferred medium
  delivers and another transiently fails, the notification is consumed. Fixing it needs per-medium
  delivery tracking, still deferred.
- **Explicit processors bypass categories**, as described under Dispatch.
- **Rows for a category removed from config are kept, not pruned.** Config edits are reversible; player
  data should not be destroyed by one. Such rows are invisible in the UI and take effect again if the
  category returns. There is no admin command to prune them.
- **No admin view.** Nothing lets an operator inspect or edit another player's matrix.
- **No inbox.** `/notifications` still covers preferences only; there is no listing of pending
  notifications and no player-initiated clear.
