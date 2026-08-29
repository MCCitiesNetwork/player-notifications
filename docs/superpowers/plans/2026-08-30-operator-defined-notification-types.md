# Operator-defined notification types — Implementation Plan

**Goal:** Let an operator declare named, rich-text notification types in `notification-types.yml` and send one with `/broadcast <content> --type <key>`.
**Spec:** `docs/superpowers/specs/2026-08-30-operator-defined-notification-types-design.md`

Tasks are ordered so each compiles and tests green on its own. Tasks 1–6 add and parameterise; Task 7
is the only one that changes observable behaviour on a server.

---

## Task 1: `unregisterPayloadMapping` on the registry

**Files:** modify `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistry.java`; test `api/src/test/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistryTest.java` (extend the existing class if present, else create).

**Interfaces:**
```java
public void unregisterPayloadMapping(@NotNull String dataType)
```

- [ ] Write the failing test: register two data types (`"a"`, `"b"`) mapping to the **same** payload class plus a renderer for that class; call `unregisterPayloadMapping("a")`; assert `dataTypes()` no longer contains `"a"`, still contains `"b"`, and `getRenderer(SharedPayload.class)` is still present.
- [ ] Run `./gradlew :api:test --tests "*NotificationDataTypeRegistryTest*"` — expect FAIL: the method does not exist (compile error).
- [ ] Implement: `this.payloadMapping.remove(dataType);` — javadoc'd as the mapping-only counterpart of `unregisterDataType`, naming the shared-payload-class case as the reason it exists.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

---

## Task 2: `CustomNotificationTypes` — the declaration file

**Files:** create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/customtype/DeclaredNotificationType.java`, `…/customtype/CustomNotificationTypes.java`, `platform/paper-plugin/src/main/resources/notification-types.yml`; test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/customtype/CustomNotificationTypesTest.java`.

**Interfaces:**
```java
public record DeclaredNotificationType(@NotNull String key, @NotNull String title,
                                       @Nullable String displayName) {}

public final class CustomNotificationTypes {
    public CustomNotificationTypes(@NotNull Logger logger)
    public void load(@NotNull ConfigurationNode root)
    public @NotNull Optional<DeclaredNotificationType> get(@NotNull String key)
    public @NotNull Set<String> keys()
    public @NotNull Optional<String> title(@NotNull String key)   // raw MiniMessage
}
```

- [ ] Write the failing test, building nodes with `YamlConfigurationLoader.builder().buildAndLoadString(...)`:
  - a good declaration (`title` + `display-name`) is present with both values;
  - `display-name` absent leaves `displayName()` null and the type present;
  - `title` absent, and `title` blank, each drop the type;
  - a `title` of `"<red"` drops the type;
  - a `display-name` of `"<red"` keeps the type with a null `displayName()`;
  - a 65-character key is dropped, a 64-character key kept;
  - keys `"Bad Key"`, `"UPPER"`, `"a<b>"` are dropped; `"a.b-c_1"` is kept;
  - a scalar node where a map was expected is skipped without throwing;
  - a second `load` of a node lacking a previously-present key removes it, and `keys()` reflects only the new file.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*CustomNotificationTypesTest*"` — expect FAIL: the classes do not exist.
- [ ] Implement `CustomNotificationTypes` on `TypeNames.load`'s idiom: a `final ConcurrentHashMap<String, DeclaredNotificationType>` never reassigned; `load` collects into a local `HashMap`, then `putAll` + `keySet().retainAll(...)` so the map is never observably empty; every rejection is one `Level.WARNING` naming the key and the reason; `KEY_PATTERN = Pattern.compile("[a-z0-9._-]{1,64}")`; MiniMessage validity via `MiniMessage.miniMessage().deserialize(value)` in a `try (ParsingException)`. Never throws.
- [ ] Write `notification-types.yml` with a header comment explaining `title`/`display-name`, the key rules, that categories live in `categories.yml`, and one fully commented-out `restart-warning` example.
- [ ] Run the same test command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

---

## Task 3: the shared payload and renderer

**Files:** create `…/customtype/CustomNotificationPayload.java`, `…/customtype/CustomTypeRenderer.java`; test `…/customtype/CustomTypeRendererTest.java`.

**Interfaces:**
```java
public record CustomNotificationPayload(@NotNull String typeKey, @NotNull String message) {}

public final class CustomTypeRenderer implements NotificationRenderer<CustomNotificationPayload> {
    public CustomTypeRenderer(@NotNull CustomNotificationTypes types)
}
```

- [ ] Write the failing test, using a `CustomNotificationTypes` loaded from a literal YAML string:
  - the rendered title equals `MiniMessage.deserialize` of the declared `title`;
  - the body equals `MiniMessage.deserialize` of the payload message (assert on a `<red>hi` payload);
  - a payload message containing `§c` renders as `Component.text` of the literal string;
  - a payload whose `typeKey` is not declared renders the title `Component.text(TypeNames.titleCase(key))`;
  - two different `target` UUIDs render identically.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*CustomTypeRendererTest*"` — expect FAIL.
- [ ] Implement both classes; the renderer resolves the title through `types.title(payload.typeKey())` on **every** render, javadoc'd as what makes an edited title reach already-stored notifications on reload.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

---

## Task 4: `CustomTypeRegistrar`

**Files:** create `…/customtype/CustomTypeRegistrar.java`; test `…/customtype/CustomTypeRegistrarTest.java`.

**Interfaces:**
```java
public final class CustomTypeRegistrar {
    public CustomTypeRegistrar(@NotNull NotificationService service,
                               @NotNull CustomNotificationTypes types,
                               @NotNull Logger logger)
    public void sync()
}
```

- [ ] Write the failing test against the existing in-tree `NotificationService` test fake in `platform/paper-plugin/src/test/java` (extend it with the two registry-backed calls if it does not already delegate to a real `NotificationDataTypeRegistry`):
  - after a first `sync()` with keys `a` and `b`, `dataTypeRegistry().dataTypes()` contains both and `getRenderer(CustomNotificationPayload.class)` is present;
  - a declared `display-name` reaches `dataTypeRegistry().displayName(key)`; an absent one leaves it empty;
  - reloading the types without `a` and calling `sync()` removes `a`, keeps `b`, and **keeps** the renderer for the shared payload class;
  - a key already mapped to another class before the first `sync()` (`registerPayloadMapping("mail", MailPayload.class)`) is skipped, its mapping unchanged, and a later `sync()` that no longer declares it does **not** unregister it.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*CustomTypeRegistrarTest*"` — expect FAIL.
- [ ] Implement: a `Set<String> registered` field holding only keys this class actually registered; `sync()` computes `desired = types.keys()`, skips a desired key whose `dataTypes()` mapping exists and is not ours (one `WARNING` naming the key), registers the rest with `registerJsonRenderable` + `registerDisplayName`, and for each key in `registered - desired` calls `unregisterPayloadMapping` and `unregisterDisplayName`.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

---

## Task 5: parameterise the broadcasters on `dataType` and title

**Files:** modify `…/broadcast/Broadcaster.java`, `…/broadcast/PersistentBroadcaster.java`; tests `…/broadcast/BroadcasterTest.java`, `…/broadcast/PersistentBroadcasterTest.java`.

**Interfaces:**
```java
// Broadcaster
public int broadcast(@NotNull Component title, @NotNull Component content, @NotNull String dataType,
                     @NotNull Collection<UUID> recipients, boolean bypass)
public @NotNull List<UUID> suppressed(@NotNull Collection<UUID> recipients, @NotNull String dataType)

// PersistentBroadcaster
public @NotNull Result broadcast(@NotNull Component title, @NotNull Component content,
                                 @NotNull String dataType, @NotNull Object payload,
                                 @NotNull Collection<UUID> recipients, boolean bypass)
```
`payload` is `Object` because the two paths store different records; `TypedNotification` is generic, so
the call site stays `new TypedNotification<>(…, dataType, payload, 0)`.

- [ ] Write the failing tests: in `BroadcasterTest`, a recipient who has silenced `"restart-warning"` but not `"broadcast"` receives nothing when `dataType` is `"restart-warning"` and receives one when it is `"broadcast"`; the recording sink observes the `title` passed in, not `MessageKeys.BROADCAST_TITLE`; `suppressed(recipients, "restart-warning")` names exactly the recipients suppressed **for that type**. In `PersistentBroadcasterTest`, the enqueued notification's `notifPayloadType` is the passed `dataType` and its payload is the passed object.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcasterTest*" --tests "*PersistentBroadcasterTest*"` — expect FAIL: existing signatures.
- [ ] Implement: thread the two values through; `Broadcaster.usableMedia(recipient, dataType)`; keep `BROADCAST_DATA_TYPE` as the constant callers pass for an untyped broadcast. Update the class javadoc where it says the type is fixed.
- [ ] Update the existing call sites in `BroadcastCommand` to pass `messages.messageFor(MessageKeys.BROADCAST_TITLE)`, `Broadcaster.BROADCAST_DATA_TYPE` and `new BroadcastPayload(arguments.content())` so behaviour is unchanged.
- [ ] Run the same command — expect PASS. Verify the count with the result XML, not the exit status (glob `*.xml`).
- [ ] Run `./gradlew build`.
- [ ] Commit.

---

## Task 6: `--type` in `BroadcastArguments`

**Files:** modify `…/broadcast/BroadcastArguments.java`; test `…/broadcast/BroadcastArgumentsTest.java`.

**Interfaces:** the record gains a trailing `@Nullable String type` component.

- [ ] Write the failing tests: `"hello --type restart-warning"` parses with content `"hello"` and type `"restart-warning"`; `"hello"` parses with a null type; `"hello --type"` yields `FlagMissingValue("--type")`; `"hello --type a --type b"` yields `"b"`; `"--type a"` alone yields `BlankContent`; `"hello --type x --persistent --limit 3"` parses all four; a message whose text contains `--type` still ends the content at that token.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcastArgumentsTest*"` — expect FAIL.
- [ ] Implement: `TYPE_FLAG = "--type"` added to `FLAG_TOKENS` and to the value-taking branch alongside `PERM_FLAG`/`CHAIN_FLAG`/`LIMIT_FLAG`, last occurrence winning. Update the class javadoc's flag list and the record's `@param` block.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

---

## Task 7: wire it up — plugin, command, messages

**Files:** modify `PlayerNotificationsPlugin.java`, `…/command/BroadcastCommand.java`, `platform/paper-plugin/src/main/resources/messages.yml`, `…/localisation/MessageKeys.java`.

**Interfaces:**
```java
// PlayerNotificationsPlugin
public @NotNull CustomNotificationTypes customTypes()

// BroadcastCommand.create(...) gains a trailing @NotNull CustomNotificationTypes parameter
```

- [ ] Write the failing test: extend `MessageKeysTest` expectations by adding the constant `MessageKeys.BROADCAST_UNKNOWN_TYPE = "broadcast.unknown-type"` **without** the YAML line, and run `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest*"` — expect FAIL: the shipped file has no such key (this is the both-directions check doing its job).
- [ ] Add to `messages.yml`, in `broadcast`'s block and in its house style: `unknown-type: "<prefix><red>There is no notification type named <white><type></white>. Declared types live in notification-types.yml."`
- [ ] Run the same command — expect PASS.
- [ ] Implement the plugin wiring: construct `CustomNotificationTypes` alongside `TypeNames`; `this.customTypes.load(copyDefaultsYaml("notification-types"))` in both the enable and the `reload()` config blocks (beside the existing `typeNames.load` lines); construct `CustomTypeRegistrar` after `startModules()` and call `sync()` there and in `reload()`; add `notification-types` to nothing in `warnAboutMissingConfigKeys` (a partial declaration map has no missing keys — the `type-names.yml` precedent).
- [ ] Implement the command wiring: after the `Parsed` case, when `arguments.type() != null`, look it up in `CustomNotificationTypes`; on a miss reply `BROADCAST_UNKNOWN_TYPE` with `MessageContainer.value("type", …)` and return 0; on a hit resolve the title with `MiniMessage.miniMessage().deserialize(declared.title())` and pass that plus the key and `new CustomNotificationPayload(key, arguments.content())` into the broadcasters. The lookup happens on the command thread, before the async task, so a rejected command costs no queries.
- [ ] Run `./gradlew build` and `./gradlew test`.
- [ ] **Manual checklist** — needs `./gradlew :platform:paper-plugin:runServer` and a reachable MariaDB; none of this is unit-testable:
  1. First enable copies `notification-types.yml` into the data folder, with every example commented out.
  2. Declare `restart-warning` with a title and a display name; restart; `/notifications preferences types` lists it under the declared display name.
  3. `/broadcast Restarting in 5m --type restart-warning` delivers in chat with the declared title.
  4. `/broadcast Hello` still delivers with the ordinary broadcast title.
  5. `/broadcast x --type nonesuch` replies with `broadcast.unknown-type` naming `nonesuch` and sends nothing.
  6. Silence `restart-warning` in the preference dialog; a typed broadcast is not pushed, an untyped one still is.
  7. `/broadcast Restarting --type restart-warning --persistent` stores it; `/notifications` shows it with the declared title.
  8. Edit the title in the file, `/notifications reload`, reopen the inbox: the **stored** entry reads with the new title.
  9. Add a second declared type, reload, and confirm both appear in the preference dialog with no restart.
  10. Delete `restart-warning` from the file and reload: it disappears from the preference dialog, and the stored notification shows the "unrenderable payload" placeholder naming the data type.
  11. Re-add it and reload: the stored notification reads correctly again.
  12. Declare a type keyed `mail`; the console warns and skips it, and `/mail` still works.
  13. Declare a type with the title `"<red"`; the console warns naming the key at load and the type does not appear.
- [ ] Commit.

---

## Task 8: documentation

**Files:** modify `CLAUDE.md`.

- [ ] Add an "Operator-defined notification types" section after "Broadcast": the file and its two keys, the shared-payload-class constraint and why the payload carries its own key, why removal uses `unregisterPayloadMapping` rather than `unregisterDataType`, the collision skip, the `--type` flag, and the deleted-type placeholder behaviour.
- [ ] Update the "Broadcast" section's flag syntax line and its `Broadcaster`/`PersistentBroadcaster` table rows to name the new parameters.
- [ ] Update "Configuration" with `notification-types.yml` and its exclusion from `warnAboutMissingConfigKeys`.
- [ ] Update "Current state" — the manual checklist above, run or not; and strike the note that the module-supplied type-name layer has no in-tree consumer, which this feature now provides.
- [ ] Update the test counts in "Testing gotchas" from a **fresh full run**, not arithmetic.
- [ ] Commit.
