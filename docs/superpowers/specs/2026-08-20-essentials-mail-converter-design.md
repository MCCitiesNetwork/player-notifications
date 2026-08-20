# EssentialsX mail converter — design

**Status:** approved, not yet built
**Module:** `platform:essentials-mail-converter`
**Related:** `2026-08-10-first-party-mail-design.md` (the mail feature this imports *into*),
`2026-07-29-discord-adapter-design.md` (the feature-module patterns reused here)

## Goal

Let an operator move a server's existing EssentialsX mailbox contents into first-party mail, once,
so that turning EssentialsX off does not throw away everybody's correspondence.

This is a **migration tool**, not an integration. It reads EssentialsX, writes PlayerNotifications,
and then has no further reason to run. Nothing about it participates in delivery, preferences,
rendering or any registry — it is deliberately off to the side of the architecture described in
"Rendering & delivery media".

## The shape of the change

A new feature module, dropped into `<dataFolder>/modules/` like `discord-adapter`. It owns:

- one Bukkit permission and one command, `/essmailconvert`,
- no schema, no config file, no sink, no processor, no renderer, no category.

The only host change is a single overload on `paper.mail.MailSender` (below), because imported mail
must keep the timestamp EssentialsX recorded rather than the moment the import ran.

### Why a module and not part of the host

The host must not carry a compile-time reference to EssentialsX for a tool most servers will run
once and never again — and the retired `platform:essentials-adapter` already established every
mechanism needed (the `paper-adapter` convention, the EssentialsX repo, `compileOnly` on
`net.essentialsx:EssentialsX`, and the class-isolation rule below).

### Why a module-owned command, despite the Discord precedent

The Discord adapter deliberately gave up owning a command and moved linking under the host's
`/notifications link`. That retreat was about **teardown**: unregistering a Bukkit command on module
stop required surgery through `Bukkit.getCommandMap().getKnownCommands()`.

This module never unregisters. It is `reloadable: false`, so its only stop is server shutdown, at
which point the command map is discarded anyway. The command is registered through Paper's
`LifecycleEvents.COMMANDS`, exactly as the host does — modules start inside `onEnable`
(`startModules()`, after `registerCommands()`), and Paper fires the COMMANDS event after enable, so a
handler registered from a module's `initialize` is still in time. This is the same ordering the host
already relies on for `AccountLinkDispatcher` resolving providers registered during `startModules()`.

The alternative considered and rejected: a host-side `MailImportRegistry` mirroring
`AccountLinkRegistry`, with `/notifications admin import <source>`. It is the architecturally
consistent choice, but it adds a host registry, a host command subtree and a host API type for a
single consumer that exists to delete itself — the opposite of the trade that made
`AccountLinkRegistry` worth it (which removed more machinery than it added).

## Architecture

Classes split by what each is allowed to mention.

### 1. `EssentialsMailConverterModule` — the entry class

Mentions **no EssentialsX type**. This is load-bearing, and is the same rule the retired adapter's
`EssentialsMailBinding` existed to enforce: the module loader resolves the manifest's entry class with
`Class.forName(name, true, moduleClassLoader)`, which links and verifies every method on it.
Verifying a method whose signature or body references an EssentialsX type forces that type to load —
*before* any runtime guard in the method body can run. With EssentialsX absent that throws
`NoClassDefFoundError`, which `ModuleLoader` does not catch (it catches `ModuleLoadException`), so it
escapes `onEnable` and takes the whole host plugin down rather than skipping one optional module.

`initialize` therefore:

1. checks `getPluginManager().isPluginEnabled("Essentials")`; if absent, logs one `INFO` line
   ("EssentialsX is not installed; nothing to convert") and returns without registering anything —
   **not** a `ModuleInitializationException`, because a converter with no source is a no-op, not a
   broken module;
2. otherwise calls `EssentialsMailBinding.register(plugin)` through its EssentialsX-free signature.

### 2. `EssentialsMailBinding` / `EssentialsMailReader` — the only classes touching EssentialsX

Both package-private. `EssentialsMailBinding` casts the plugin instance to
`com.earth2me.essentials.IEssentials`, constructs the reader and the command, and registers the
command handler on `plugin.getLifecycleManager()`.

`EssentialsMailReader` is where every EssentialsX call lives:

```java
IUserMap users = essentials.getUsers();
Set<UUID> ids = users.getAllUserUUIDs();
User user = users.loadUncachedUser(uuid);     // uncached: this is a one-shot sweep, not a hot path
List<MailMessage> mail = user.getMailMessages();
```

`loadUncachedUser` is the method EssentialsX documents for exactly this ("ideally to be used when
running operations on all stored users") — it does not pollute the user cache with thousands of
accounts that were only read once.

**The read runs on the main thread, in chunks.** EssentialsX user loading is not thread-safe (the
retired `EssentialsMailSink` marshalled onto the main thread for the same reason), but loading a few
thousand userdata files in one tick would stall a live server for seconds. The reader therefore
processes a fixed number of UUIDs per tick (`USERS_PER_TICK = 100`) via a repeating task, accumulating
into a plain list, and hands the finished list to a completion callback exactly once. A server with
20k userdata files converts in ~10 seconds of wall clock with no single tick longer than a normal one.

Each `MailMessage` is flattened into an **`ImportedMail`** record — declared in the EssentialsX-free
package, containing no EssentialsX type — so everything downstream is testable without a server:

```java
record ImportedMail(UUID recipient, UUID sender, String senderName, String message,
                    Instant sentAt, boolean read, boolean expired) {}
```

`ImportedMail` owns the static factory that performs the legacy flattening and `§`-stripping, so that
logic is unit-testable without an `EssentialsMailReader` and without EssentialsX on the classpath.

### 3. `EssentialsMailConverter` — pure, and where the rules live

No Bukkit, no EssentialsX, no scheduler. Takes `List<ImportedMail>`, a `MailSender` and a
`NotificationService`, returns a `ConversionReport`. Runs on the async executor, because
`enqueueNotification` and `markSeen` do blocking JDBC.

```java
record ConversionReport(int imported, int skippedExpired, int skippedBlank, int failed) {}
```

For each mail: skip, or `mailSender.send(sender, senderName, recipient, message, sentAt)` and then,
when `read`, `service.markSeen(key, recipient)`.

## Mapping rules

Each row is a decision, not an accident.

| EssentialsX | Result | Why |
|---|---|---|
| `isExpired()` | skipped, counted | Essentials would not have shown it in `/mail` either; importing it would resurrect mail the recipient was never going to see. |
| unexpired, `getTimeExpire() != 0` | imported with `notifExpiryTime = null` | Matches first-party mail, which sets null deliberately: mail is correspondence and must not be swept by the prune task. The Essentials expiry is dropped, not honoured — see Known limitations. |
| `isLegacy()` | `senderName = "Unknown"`, `sender = UNKNOWN_SENDER`, `message` = the raw string with `§` colour codes stripped | A legacy mail's `getMessage()` **is** the entire formatted line, sender prefix and colours included; there is no separate sender field to recover. Stripping the codes keeps `MailRenderer`'s `Component.text(...)` from displaying raw `§` noise. |
| modern, `getSenderUUID() == null` | `sender = UNKNOWN_SENDER`, real `getSenderUsername()` kept | `MailPayload.sender` is `@NotNull`; console-sent and pre-UUID mail has no sender id. |
| blank message | skipped, counted | `MailPayload` rejects blank outright. |
| message over `MailPayload.MAX_MESSAGE_LENGTH` | **imported in full, untruncated** | The record only rejects blank; 256 is a `/mail send` input rule enforced by `MailRecipients`, not a storage constraint. Truncating somebody's archived mail to satisfy a rule that did not exist when it was written would be silent data loss. |
| `isRead()` | imported, then `markSeen` | Otherwise the entire archive arrives unread and the recipient's next join announces hundreds of "unread" mails they read years ago. |

`UNKNOWN_SENDER` is `new UUID(0L, 0L)` — the nil UUID, which cannot collide with a real account.
`MailRenderer` only ever displays `senderName`, so the sentinel is never shown; it exists because
`MailPayload.sender` is non-null and is reserved for a future reply command, which will need to
recognise it as "cannot reply".

**No arrival notice fires.** `MailNotifier` is not involved at any point: telling a player "You have
new mail!" for a five-year-old message would be false, and doing it once per imported mail would spam
every Discord DM on the server.

## The one host change

```java
// paper.mail.MailSender
public @NotNull String send(UUID sender, String senderName, UUID recipient, String message);          // existing
public @NotNull String send(UUID sender, String senderName, UUID recipient, String message,
                            @NotNull Instant sentAt);                                                  // new
```

The existing three-argument form becomes a delegation with `Instant.now()`, so no caller changes.
`sentAt` becomes the notification's `notifScheduledTime`, which is the inbox's primary sort key
(`ORDER BY n.notifScheduledTime DESC, n.notifPriority DESC, n.notifKey DESC`) — without it every
imported mail would carry the import's timestamp, and a decade of correspondence would arrive in
arbitrary order at the top of the inbox, above genuinely new mail.

Keys stay `"mail-" + UUID.randomUUID()`, as for a normal send.

## The command

`/essmailconvert` — permission `essentialsmailconverter.command.convert`, gated with Brigadier's
`requires`. The permission is **not declared anywhere**: `paper-plugin.yml` belongs to the host, and
the Discord adapter's programmatic permission registration was deleted for good reason. An undeclared
permission resolves through Bukkit's default, which is op-only — exactly the intended gate — and an
operator who wants to grant it to a rank can define it in their permissions plugin. Usable from
console as well as in-game: it operates on server data, not on the sender.

| Command | Behaviour |
|---|---|
| `/essmailconvert preview` | Reads, reports what *would* happen, writes nothing. |
| `/essmailconvert confirm` | Reads and imports. |
| `/essmailconvert` | Prints what the two subcommands do, and the warning below. Imports nothing. |

**The bare command does nothing on purpose, and `confirm` is a literal rather than a flag**, because
the conversion is deliberately **not idempotent**: running it twice imports every mail twice. That
follows from the decision to leave the EssentialsX copy untouched (a read-only migration is
re-runnable and destroys nothing on a bad import), and de-duplication was rejected as costing either
a fingerprint table or a full scan of every existing mail notification, for a command an operator runs
once. The bare form's reply says so in as many words, and `preview` exists so an operator can see the
numbers before committing.

Only one conversion may run at a time (an `AtomicBoolean` guard); a second invocation while one is in
flight is refused with a message rather than queued.

## Error handling

- **EssentialsX absent or disabled** — module logs and registers nothing; the command does not exist.
  A server that never had EssentialsX sees no trace of this module.
- **A single user failing to load, or `getMailMessages()` throwing** — logged at `WARNING` naming the
  UUID, that user skipped, the sweep continues. One corrupt userdata file must not abort a 20k-account
  migration.
- **A single mail failing to enqueue** — logged at `WARNING`, counted in `ConversionReport.failed`,
  the rest continue. Every mail is its own `enqueueNotification` transaction already, so a failure is
  naturally isolated.
- **`markSeen` failing after a successful send** — logged, *not* counted as a failure: the mail is
  imported and readable, it merely shows as unread. Re-running is not an acceptable remedy for this
  (it would duplicate), so the report says so.
- The final report is sent to the command sender and logged, so a console run and an in-game run leave
  the same record.

## Testing

Testable, and tested:

- `EssentialsMailConverterTest` — every mapping-rule row above, against a recording
  `NotificationService`: expired skipped, blank skipped, over-length message imported whole, read mail
  marked seen, timestamps preserved, a throwing enqueue counted in `failed` without stopping the run.
- `ImportedMailTest` — the `§`-stripping and legacy flattening factory, which is where a bad regex
  would silently mangle every legacy mail.
- `MailSenderTest` — one added case: the `Instant` overload stores that instant as
  `notifScheduledTime`, and the existing overload still uses "now".

Not testable without a live server, and therefore on a manual checklist in the plan:

- `EssentialsMailConverterModule` / `EssentialsMailBinding` — module class loading, the EssentialsX
  presence check, and the `NoClassDefFoundError` isolation property (which can only be *observed* by
  running the module on a server without EssentialsX).
- `EssentialsMailReader` — needs a real `IUserMap` and real userdata.
- The Brigadier command, the permission gate, and the chunked main-thread sweep's tick behaviour.

## Known limitations

- **Not idempotent.** Running `confirm` twice imports everything twice. Deliberate; see above.
- **Essentials per-mail expiry is dropped.** An unexpired mail with a future `timeExpire` is imported
  with no expiry and will never be pruned. First-party mail has no expiry concept, and adding one for
  imported mail only would mean two classes of mail behaving differently in the same inbox. If mail
  expiry is ever added generally, this is the place to revisit.
- **Colour and formatting are lost, and that is now a safety property as well as a stylistic one.**
  Legacy `§` codes are stripped, and the whole message is then run through `MiniMessage#escapeTags`
  before storage. Mail is stored as MiniMessage source and parsed on read; live mail is gated by
  `MailFormatting.sanitize`'s per-tag permissions, but imported mail passed through no such gate, so
  storing it raw would let a years-old message reading `<red>` or `<click:run_command:...>` become live
  formatting or a live click event on import. Every imported mail is plain text by construction — which
  is all EssentialsX mail ever was. This was added when the branch was rebased onto the MiniMessage mail
  work; before that, `MailRenderer` built bodies with `Component.text(...)` and raw storage was safe.
- **No reverse direction.** Nothing writes back to EssentialsX, and there is no un-import.
- **No per-player conversion.** The sweep is all-or-nothing. A `/essmailconvert confirm <player>` form
  is a plausible follow-up but would make the non-idempotency harder to reason about, since an
  operator could then half-convert a server.
- **Mail sent *while* a conversion is running** is a race nobody will hit (the sweep is seconds, and
  the source plugin is being retired), and is not guarded against.
