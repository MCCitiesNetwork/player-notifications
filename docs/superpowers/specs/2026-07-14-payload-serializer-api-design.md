# Payload Serialization API — Design

**Date:** 2026-07-14
**Status:** Approved (design), pending implementation plan

## Problem

Notification payloads are persisted as a `String` in the `notifPayload JSON` column, but there is
no serialization contract:

- **Write side has none.** `NotificationService.enqueueNotification` takes an already-serialized
  `String`. The caller hand-serializes, with nothing enforcing that the result is valid JSON.
- **Read side is String-only.** `NotificationDelivery.decodePayload` only handles `String` payload
  types; any typed payload is logged and retained rather than delivered. Its WIP `TypeSerializer` /
  `ConfigurationLoader` fields are an incomplete stab at this.

Payloads **must** be JSON serializable. We want a public way for third-party module authors to
register payload types and have them (de)serialized to/from JSON, **without** those authors taking a
compile-time dependency on the underlying JSON library, and **without** hard-welding the plugin to a
single backend (Jackson today, potentially Gson later).

## Decision summary

- Expose a neutral, dependency-free `PayloadSerializer<T>` interface in the `api` module.
- Back the default implementation with **jackson-databind**, kept fully private inside `core` and
  relocated by shadow. The mapper never appears in any public signature.
- Object mapping is delivered as a convenience whose *product* is the neutral api type, so authors
  get reflective record/POJO ↔ JSON for free while importing only `api` types.
- Add a typed write path (`TypedNotification<T>`) so enqueue serializes through the same contract.
- Route delivery's decode through the registry's serializer lookup.

Rejected alternatives: exposing Jackson's `ObjectMapper` directly (forces third parties onto a
relocated Jackson and welds the API to one backend); adopting Configurate `TypeSerializer` as the
public contract (leaks `configurate-core` into the dependency-light `api` and makes the contract
node/loader-shaped rather than "JSON string ↔ T").

## Components

### 1. `PayloadSerializer<T>` — `api`, new package `api.serialize`

```java
package io.github.md5sha256.playernotifications.api.serialize;

public interface PayloadSerializer<T> {
    @NotNull String serialize(@NotNull T payload);   // T → JSON stored in notifPayload
    @NotNull T deserialize(@NotNull String json);    // JSON from DB → T
}
```

Pure; no dependencies beyond the JDK + annotations. This is the **only** serialization type that
crosses the api boundary.

### 2. `PayloadSerializationException` — `api.serialize`

Unchecked (`extends RuntimeException`). Thrown when serialization fails or when a typed enqueue finds
no serializer registered for its data type.

### 3. `NotificationDataTypeRegistry` changes — `api`

Add a third map, `Class<?> → PayloadSerializer<?>`, keyed by payload class (mirroring `processors`):

- `<T> void registerSerializer(Class<T> type, PayloadSerializer<T> serializer)`
- `void unregisterSerializer(Class<?> type)`
- `<T> Optional<PayloadSerializer<T>> getSerializer(Class<T> type)`
- `Optional<? extends PayloadSerializer<?>> getSerializer(String dataType)` (resolves class first,
  mirroring the dual `getProcessor`)
- `unregisterPayloadMapping(dataType)` also drops the serializer, matching how it already cascades
  to the processor.

### 4. Default reflective serializer — `core`

`DefaultNotificationService` (or a small `core.serialize` helper it owns) holds one **private**
jackson-databind `ObjectMapper` and a factory returning the neutral type:

```java
<T> PayloadSerializer<T> reflective(Class<T> type) {
    return new PayloadSerializer<T>() {
        public String serialize(T p)   { return mapper.writeValueAsString(p); }   // wraps errors in PayloadSerializationException
        public T deserialize(String j) { return mapper.readValue(j, type); }
    };
}
```

The mapper is configured with `findAndRegisterModules()` so any Jackson module present on the
(shaded) classpath is picked up; a `java.time` module (jsr310) is added only if payloads need it.

Exposed to authors through one convenience on `NotificationService` (api-typed signature,
core implementation):

```java
<T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                             @NotNull NotificationProcessor<T> processor);
```

This atomically binds the data-type mapping, a default reflective serializer, and the processor.
Authors never import Jackson. Authors with exotic needs drop to the granular registry methods and
supply their own `PayloadSerializer<T>` (full control, still no forced dependency).

A default `PayloadSerializer` for `String.class` is registered at construction — it is simply the
reflective serializer for `String`, so a bare string is JSON-quoted on write and unquoted on read.
This keeps the `JSON` column valid and closes the "String processors receive JSON-encoded form" note
in CLAUDE.md.

### 5. Typed write path — `api` + `core`

New record mirroring `ResolvedNotification` but with a typed payload:

```java
public record TypedNotification<T>(
    @NotNull String notifKey,
    @NotNull Instant notifScheduledTime,
    @Nullable Instant notifExpiryTime,
    @NotNull NotificationTarget notifTarget,
    @NotNull String notifPayloadType,
    @NotNull T notifPayload,
    int notifPriority) {}
```

New service method:

```java
<T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed);
```

Implementation: look up the serializer for `notifPayloadType` (throw `PayloadSerializationException`
if none), serialize `notifPayload` to a `String`, build the existing string-form
`ResolvedNotification`, and delegate to the current persistence path. The existing string-based
`enqueueNotification(ResolvedNotification, boolean)` stays for callers who already hold JSON.

### 6. Read path — `core`

`NotificationDelivery.decodePayload` drops its `String`-only special case **and** the WIP
`payloadSerializers` list, `registerSerializer`/`unregisterSerializer` methods, and `loader` field.
It becomes:

```java
Optional<? extends PayloadSerializer<?>> ser = registry.getSerializer(payloadClass);
if (ser.isEmpty()) { warn("no serializer for " + type); return RETAIN; }
try { return ser.get().deserialize(rawPayload); }
catch (RuntimeException e) { warn(...); return RETAIN; }
```

## Data flow

**Enqueue:** `registerJsonPayload(type, Class, processor)` once at startup → later
`enqueueNotification(TypedNotification<T>, overwrite)` → service serializes payload via the
registered `PayloadSerializer` → persists the JSON string in `notifPayload`.

**Deliver:** `NotificationDelivery.deliver(uuid)` → for each due notification, resolve payload class +
serializer + processor from the registry → `deserialize(rawPayload)` → invoke processor per target →
prune targets returning `DELETE`.

## Error handling

- **Serialize (enqueue):** failure or missing serializer → throw `PayloadSerializationException`.
  The caller supplied the data; fail fast.
- **Deserialize (delivery):** any failure is caught, logged, and the notification is `RETAIN`ed — a
  single poison payload never crashes the delivery loop or silently drops the notification.
- **Missing serializer (delivery):** warn + `RETAIN`, consistent with the existing missing-processor
  behavior.

## Dependencies & build

- `core/build.gradle.kts`: **remove** `configurate-jackson` (it only provides streaming, not object
  mapping); **add** `implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")` (bump as
  desired). It is
  `implementation`, not `api`, because no public signature exposes Jackson.
- `platform/paper-plugin/build.gradle.kts`: add `relocate("com.fasterxml.jackson",
  "${base}.com.fasterxml.jackson")` to the existing shadow relocation block.
- `api/build.gradle.kts`: unchanged — `PayloadSerializer` is pure.

## Relocation caveat (explicitly out of scope)

Because Jackson is relocated, the shared mapper cannot honor **Jackson annotations** authored against
the un-relocated `com.fasterxml.jackson.*` packages on a third party's payload class. This is
accepted: plain records/POJOs map by reflection (relocation-safe, no annotations), and any
annotation-driven or otherwise custom case is served by the author implementing `PayloadSerializer<T>`
directly. We deliberately do **not** un-relocate or expose Jackson to support third-party annotations.

## Compatibility note

Existing rows / test fixtures that stored **raw** (non-JSON-encoded) strings will fail
`deserialize` under the String reflective serializer (a bare word is not valid JSON). The project is
pre-release; affected tests are updated to enqueue through the typed path. No data migration is built.

## Testing

- `api`: unit tests for registry serializer registration/lookup and cascade-on-unregister.
- `core` (Testcontainers): round-trip a typed payload through `enqueueNotification(TypedNotification)`
  → DB → `NotificationDelivery.deliver`, asserting the processor receives the decoded object;
  String-payload round-trip; missing-serializer → RETAIN; deserialize failure → RETAIN + no throw;
  serialize failure on enqueue → `PayloadSerializationException`.

## Out of scope

- Switching the `notifPayload` column type (stays `JSON`).
- A Gson backend (the seam supports it; not implemented now).
- Concurrency-safe target-id allocation (pre-existing, unrelated).
