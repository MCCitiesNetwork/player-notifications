---
name: debug
description: Use on any bug, test failure, build failure, or unexpected behaviour in this plugin, before proposing a fix. Replaces superpowers systematic-debugging.
---

# Debug

Root cause before fix. A symptom fix is a failure.

> The tables below are starting points, not truth. Confirm the mechanism in the
> code before acting on any row — entries describing current behaviour go stale as
> the plugin changes, and a fixed bug listed here will send you down a dead end.

## 1. Investigate

- Read the whole error and stack trace. Note the file, line, and exception type —
  they usually name the answer.
- Reproduce it. If you can't trigger it reliably, gather data; don't guess.
- `git diff` / recent commits — what changed?
- Trace the bad value **backwards** to where it originates. Fix at the source.

For a multi-layer failure, instrument each boundary once and read the evidence
before theorising. This plugin's layers, as a map of where to instrument: enqueue →
persistence → resolution → dispatch → render → sink delivery. Log what enters and
leaves each, then investigate only the layer that broke.

## 2. Where things usually break

**Durable — these follow from the architecture, and change only if the design does.**
Re-read the named class to confirm the current rule before relying on it.

| Symptom | Look at |
| --- | --- |
| Notification never arrives | dispatch precedence in `NotificationDelivery` — which of processor / renderer / fallback claimed it |
| Delivered to the wrong medium | the preference resolution order in `DatabaseNotificationPreferences` — exact key, then blanket fallback, then the configured default |
| Payload decodes wrong | the column type backing the payload and whether the serializer round-trips it |
| Migration didn't run | the script exists but was never registered in the migrator's step list; or the script tripped its statement splitter |
| Module never loads | manifest keys must be the `ModuleManifest` field names verbatim, and its expected host class must match the plugin's runtime FQCN |
| Registry lookup misses | registration ran after the consumer read the registry — check the ordering in the plugin's enable sequence |
| Suite fails wholesale, no individual red test | a discovery failure: missing test-runtime dependency, or an unavailable external service the suite needs |

**Current-state quirks — deliberate trade-offs or known gaps, each of which may
have been fixed since this was written.** `CLAUDE.md`'s "Current state" and the
design docs under `docs/superpowers/specs/` are authoritative; check there first,
and delete a row here once it stops being true.

| Symptom | Suspect |
| --- | --- |
| Muted player still messaged | a bespoke processor winning dispatch precedence and bypassing preferences |
| Delivered, but one medium missed it | fan-out consumes the notification when *any* sink succeeds; partial delivery is silent |
| Preference rows invisible in the UI but still stored | rows for a category removed from config are kept, not pruned |
| Duplicate or clashing ids under concurrent writes | id allocation that reads-then-increments rather than relying on the database |

If the bug you're chasing *is* one of these documented trade-offs, that's a design
conversation with the user, not a fix to make unilaterally.

## 3. Compare

Find working code doing the same thing in this repo and list every difference,
however small. Read a reference implementation completely — never skim and adapt.

## 4. Hypothesise

State it: "X is the root cause because Y." Test it with the **smallest** possible
change, one variable at a time. Wrong? Form a new hypothesis — do not stack
another fix on top.

**Three failed fixes = the design is wrong, not the hypothesis.** Stop and raise
it with the user instead of trying a fourth.

## 5. Fix

Write a failing test reproducing the bug first (see `implement`), then make one
change addressing the root cause. No bundled refactoring. Verify with `ship`.

If the root cause turns out to be a stale statement in `CLAUDE.md` or in one of
these tables, correct that too — a wrong map costs the next session more than the
bug did.

## Red flags

"Quick fix for now", "just try changing X", "probably X", "I'll verify manually",
proposing fixes before tracing the data flow, or listing several fixes at once.
All mean: go back to step 1.
