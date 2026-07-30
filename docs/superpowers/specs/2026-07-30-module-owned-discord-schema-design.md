# Module-owned Discord link schema — design

**Date:** 2026-07-30
**Status:** implemented (plan: `docs/superpowers/plans/2026-07-30-module-owned-discord-schema.md`)
**Amends** `2026-07-30-embedded-discord-linking-design.md`, retiring its "A Discord-side table lives in
`core`" known limitation and the reasoning behind it.

## Goal

Move Discord account linking wholly into `platform:discord-adapter`. After this change `core` contains no
Discord-shaped type, table, mapper or migration, and a server with no Discord module has no
`DiscordAccountLink` table.

The adapter owns its **schema and its migration logic**; it reuses the host's **connection pool, MyBatis
session factory and type handlers**. Nothing else about the link feature changes — `DiscordLinkFlow`,
`LinkCodeService`, `EmbeddedDiscordAccountProvider`, the commands and the sink are untouched.

### Non-goals

- No generic per-module migration facility in `core`. This adapter gets its own migrator; if a second
  module later needs one, *that* is when a shared abstraction is worth extracting.
- No change to `core`'s own `schema_version` table, its migrator, or its migration numbering beyond
  deleting the V2 step that is being moved out.
- No new method on `Database` or `SqlSessionWrapper`.

## Why this shape

### The seam already exists — no `core` API change is needed

`SqlSessionWrapper#session()` is already public, and `SqlSession` exposes everything required:

| Need | Existing API |
|---|---|
| A connection for DDL | `session().getConnection()` |
| Register the adapter's mapper | `session().getConfiguration().addMapper(...)`, guarded by `hasMapper` |
| Use it | `session().getMapper(...)` |
| `UUID` ↔ `BINARY(16)` | already registered on the shared `Configuration` by `MariaDatabase` |

So the earlier claim — "a feature module cannot own a migration today" — was **too strong**. It is true
that a module cannot add a step to `MariaSchemaMigrator.DEFAULT_MIGRATIONS` or a mapper to
`MariaDatabase`'s hardcoded list. It does not follow that a module cannot own a migration at all: it can
run its own DDL and track its own versions over the host's pool. That is what this change does, and it is
why no `Database` method is being added.

Reusing the pool rather than opening a second one matters for a Minecraft plugin: a separate pool would
double idle connections against the operator's `max_connections` for one small table, and would need its
own copy of `database.yml` credentials.

### The adapter tracks its own versions in its own table

`discord_schema_version`, owned by and named for the module, with the same shape as core's
`schema_version` (`version` primary key, `description`, `applied_at`). Two independent chains, each
starting at V1, that cannot collide.

Chosen over adding a `namespace` column to core's `schema_version` because that would mean changing
core's bootstrap DDL and backfilling existing rows — a change to `core` in service of a module, which is
the exact coupling this work exists to remove. The cost is that reading total schema state means looking
at two tables; that is acceptable and self-explanatory, since each table is named after its owner.

### Resources load through the module's own class loader

Core's `MariaSchemaMigrator.loadResource` uses `MariaSchemaMigrator.class.getClassLoader()`, which cannot
see inside the module jar. The adapter's migrator therefore resolves scripts with
`DiscordSchemaMigrator.class.getClassLoader()`, and its script ships in the *module* jar at
`sql/discord/V1__discord_account_link.sql`. This is the same reason `ModuleConfigs` exists rather than
reusing the host's `copyDefaultsYaml`.

### Scripts are split on `;` and executed statement by statement

Core's migrator relies on `allowMultiQueries=true`, which it sets on a pool it builds itself. The
adapter borrows the *host's* pool and must not assume that flag is on its URL, so it splits each script
on `;` and executes the statements individually. Consequence, inherited from core and worth stating
because it is a trap: **a trigger or procedure body containing `;` cannot be expressed in one of these
scripts.** Core hit exactly this and works around it with a single-statement trigger body. The adapter's
only table needs no trigger, so this costs nothing today.

## Architecture

```
DiscordModule.initialize
  ├─ DiscordSchemaMigrator.migrate(database, logger)      -- CREATE TABLE, record V1
  │     └─ database.openSession(true).session().getConnection()
  └─ DatabaseDiscordAccountLinkStore(database, clock)
        └─ per call: openSession() → registerMapperOnce → session().getMapper(...)
```

Both use the host `Database` obtained from `plugin.database()`. The host neither knows nor cares.

## Types and files

### Removed from `core` (every one of these was added earlier today)

| File | Action |
|---|---|
| `core/src/main/resources/sql/migrations/V2__discord_account_link.sql` | delete |
| `.../maria/MariaSchemaMigrator.java` | remove the `MigrationStep(2, …)` entry, restoring a single-step `DEFAULT_MIGRATIONS` |
| `.../database/entity/DiscordAccountLinkEntity.java` | move to the adapter |
| `.../database/mapper/DiscordAccountLinkMapper.java` | move to the adapter |
| `.../database/maria/mapper/MariaDiscordAccountLinkMapper.java` | move to the adapter |
| `.../database/SqlSessionWrapper.java` | remove `discordAccountLinkMapper()` |
| `.../database/maria/MariaSqlSession.java` | remove the override |
| `.../database/maria/MariaDatabase.java` | remove `addMapper(MariaDiscordAccountLinkMapper.class)` |
| `core/src/test/.../DiscordAccountLinkMapperTest.java` | move to the adapter |
| `core/src/test/.../AbstractDatabaseTest.java` | remove `TRUNCATE TABLE DiscordAccountLink` |
| `core/src/test/.../SchemaUpgradeTest.java` | **keep**, retargeted (see below) |

`SchemaUpgradeTest` is kept rather than deleted. Its subject is core's migrator, not the Discord table:
it covers applying a chain to a database already recorded at V1, an idempotent re-run, and the refusal of
a `schema_version` newer than the supported chain. With V2 gone, core's chain has one step again, so the
"applies V2 on top of V1" case is rewritten to assert that a database already at V1 needs no further
work and that `DiscordAccountLink` is **not** created — which is now the property worth guarding.

### Added to `platform:discord-adapter`

| File | Purpose |
|---|---|
| `src/main/resources/sql/discord/V1__discord_account_link.sql` | the DDL, verbatim from core's V2 |
| `discord/schema/DiscordSchemaMigrator.java` | applies the chain, tracks `discord_schema_version` |
| `discord/schema/DiscordMigrationStep.java` | `record (int version, String description, String resourcePath)` |
| `discord/schema/DiscordAccountLinkEntity.java` | moved from core, package changed |
| `discord/schema/DiscordAccountLinkMapper.java` | moved from core, package changed |
| `discord/schema/MariaDiscordAccountLinkMapper.java` | moved from core, package changed |
| `build.gradle.kts` | add Testcontainers to `testImplementation` |

A `schema` subpackage, because these are the module's persistence internals and the existing flat
`discord` package is already large.

`DiscordMigrationStep` duplicates core's `MigrationStep` record. Deliberate: reusing core's would be a
`core` type in the adapter's own migrator signature, reintroducing coupling in the opposite direction for
the sake of three fields.

### Changed in `platform:discord-adapter`

- **`DatabaseDiscordAccountLinkStore`** — resolves its mapper via `session().getMapper(...)` after a
  one-time `registerMapperOnce` (`hasMapper` check then `addMapper`, on the shared `Configuration`),
  instead of the deleted `wrapper.discordAccountLinkMapper()`. The check is required, not defensive:
  `addMapper` throws if the type is already bound, and the store is constructed once but used many times.
- **`DiscordModule.initialize`** — calls `DiscordSchemaMigrator.migrate(plugin.database(), logger)` before
  constructing the store, and fails module startup via `ModuleInitializationException` if it throws.
  Ordering matters: the store must not be handed to the provider before its table exists.

## Behaviour

`DiscordSchemaMigrator.migrate(Database, Logger)`:

1. Open a session with auto-commit and take its `Connection`.
2. `CREATE TABLE IF NOT EXISTS discord_schema_version (version INT NOT NULL PRIMARY KEY, description VARCHAR(255) NOT NULL, applied_at DATETIME NOT NULL DEFAULT NOW())`.
3. Read `COALESCE(MAX(version), 0)`.
4. If that exceeds the highest known step, throw `SQLException` — an operator who downgrades the module
   must be told, not silently left on a schema whose columns the running code does not know.
5. For each step above the current version, load the script through the module's class loader, split on
   `;`, execute each non-blank statement, then insert the version row.
6. Log each applied step at `INFO`, matching core's migrator.

Idempotent: a second run applies nothing. Migration failure propagates, so the module refuses to start
rather than registering a sink whose link lookups would fail on every call.

## Error handling

- A failed migration throws `SQLException`/`IOException`; `DiscordModule` wraps it in
  `ModuleInitializationException`. The host's lifecycle manager reports the module as failed and the rest
  of the plugin keeps working — a Discord adapter with no table is strictly worse than no Discord adapter.
- A missing script resource throws `IOException` naming the path, the same as core's migrator.
- `registerMapperOnce` is guarded by `hasMapper`, so repeated store calls cannot throw on double
  registration. It mutates the host's shared `Configuration`, which is a deliberate, documented
  narrow coupling — MyBatis has no per-session mapper scope.
- Store failures are unchanged: they propagate as `PersistenceException` and are already handled by
  `ChainedDiscordAccountProvider` (logs, tries the next provider) and `DiscordLinkFlow` (returns `FAILED`).

## Testing strategy

The adapter gains Testcontainers, which converts two previously untestable classes into tested ones:

- **`DiscordSchemaMigratorTest`** (new, Testcontainers `mariadb:11.7`) — a fresh database gets the table
  and a recorded V1; a second run is a no-op; a `discord_schema_version` recording a version above the
  known chain is refused; the migrator works against a database where core's own migrations have already
  run, proving the two chains do not interfere.
- **`DiscordAccountLinkMapperTest`** (moved from `core`, unchanged assertions) — now runs in the adapter,
  against a schema created by the adapter's own migrator rather than core's.
- **`DatabaseDiscordAccountLinkStoreTest`** (new) — the store against a real database: round-trip,
  `link` replacing both sides, `unlink`'s return value. This is the class the earlier design listed as
  "untested by automated tests"; it no longer is.

Everything already covered stays covered: `LinkCodeServiceTest`, `DiscordLinkFlowTest`,
`EmbeddedDiscordAccountProviderTest`, `DiscordLinkCommandTest`, `ChainedDiscordAccountProviderTest`,
`DiscordSettingsTest`. `FakeDiscordAccountLinkStore` is unaffected, since `DiscordAccountLinkStore` does
not change.

`:platform:discord-adapter:test` now **requires a running Docker daemon**, like `:core:test`. That is a
real cost — a previously fast, hermetic suite now needs a container — and it is accepted because the
alternative is leaving the store and migrator permanently unverified.

## Migrating an existing dev database

A database that ran today's earlier commits has core `schema_version` row `version = 2`. Core's chain
drops back to one step, so the migrator's downgrade guard would refuse to start with "Database schema
version 2 is newer than the maximum supported version 1".

One statement fixes it:

```sql
DELETE FROM schema_version WHERE version = 2;
```

The `DiscordAccountLink` table itself can stay: the adapter's `CREATE TABLE IF NOT EXISTS` adopts it and
records its own V1 against it. No data is lost, and nothing needs dropping. This is a one-time step for a
schema that was never released.

## Known limitations

- **The adapter mutates the host's shared MyBatis `Configuration`** to register its mapper. MyBatis has
  no per-session mapper scope, so there is no narrower option short of hand-writing JDBC. It is
  idempotent and guarded, but two modules registering the *same* mapper class would still be a conflict —
  not reachable today, since a mapper class lives in exactly one module.
- **No shared per-module migration facility.** The next module needing one will either duplicate
  `DiscordSchemaMigrator` or motivate extracting it. Duplicating twice is the signal to extract; doing it
  now would be speculative.
- **Scripts cannot contain a statement with an embedded `;`** (trigger or procedure bodies), because the
  migrator splits on it. Core has the same constraint for the same reason and works around it with a
  single-statement trigger body.
- **`:platform:discord-adapter:test` now needs Docker.** Previously it did not.
- **The link feature's own gaps are unchanged** — no admin link management, no rate limiting, codes not
  persisted, one Discord account per player, and the end-to-end path still unverified by hand. See
  `2026-07-30-embedded-discord-linking-design.md`.
