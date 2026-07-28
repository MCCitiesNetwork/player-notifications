---
name: implement
description: Use when writing any feature or bugfix code in this plugin, and when executing a written implementation plan - test-first cycle plus this repo's build and test commands. Replaces superpowers test-driven-development + executing-plans + subagent-driven-development.
---

# Implement

Test first. Watch it fail. Minimal code to pass. Commit. Execute inline — no
subagent per task.

> Anything below describing *the repo as it is* may have drifted. The code and
> `CLAUDE.md` outrank this file; when they disagree with it, they win and you
> should fix this file in the same commit. The process rules are not repo facts —
> they hold regardless.

## The Iron Law

```
NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST
```

If you didn't watch the test fail, you don't know it tests the right thing.

Wrote the code before the test? **Delete it and start from step 1.** Not "keep it
as reference", not "adapt it while writing the test" — that is testing after.
Delete means delete, then implement fresh from the test.

Violating the letter of this is violating the spirit of it.

## The cycle

1. **Write one failing test.** One behaviour, name describes it, real code over
   mocks. Before writing it, name the production change that would make it fail —
   if you can't, the test asserts nothing.
2. **Run it and read the failure.** Mandatory, never skipped. It must fail for the
   missing feature, not a typo or a missing-class error. Passing immediately means
   you're testing existing behaviour — fix the test. Erroring means fix the error
   and re-run until it fails *correctly*.
3. **Minimal implementation.** Simplest thing that passes. No options parameters,
   no hooks, no "while I'm here" refactors.
4. **Run it again — PASS**, module suite still green, output clean of new
   warnings. Failing? Fix the code, not the test.
5. **Refactor** only while green — dedupe, rename, extract. No new behaviour.
6. **Commit.**

## Rationalizations

| Excuse | Reality |
| --- | --- |
| "Too simple to test" | Simple code breaks. The test costs 30 seconds. |
| "I'll test after" | Tests written after pass immediately, which proves nothing. You never watched it fail, so you never proved it can catch the bug. |
| "Already verified it on a live server" | Manual checks aren't re-runnable and aren't recorded. Fine as *extra* evidence, never as the test. |
| "Deleting this hour of work is wasteful" | Sunk cost — that time is spent either way. Keeping code you can't trust is the actual waste. |
| "Test is hard to write" | Listen to it. Hard to test means hard to use; fix the interface. |
| "It's early days, TDD is overkill" | The green suite is what makes refactoring here safe. That gets *more* valuable as the plugin grows, not less. |

**Red flags — stop and restart the cycle:** code written before its test; a test
that passes on first run; not being able to say why the test failed; "just this
once"; "it's about the spirit, not the ritual"; "this case is different because…".

## Before calling a task done

- Every new method has a test, and you watched each one fail first
- Each failed for the *expected* reason
- Implementation is minimal — nothing added beyond what a test demanded
- Full suite green, no lower than the baseline you recorded, output clean
- Real code, mocks only where unavoidable
- Edge cases and error paths covered, not just the happy path

## Commands

```
./gradlew build                            # all modules
./gradlew test                             # every module's suite
./gradlew :<module>:test                   # one module
./gradlew :<module>:test --tests "<ptn>"   # one class or method
./gradlew :platform:paper-plugin:runServer # live Paper test server
```

Modules come from `settings.gradle.kts` — read it rather than assuming the current
set. `CLAUDE.md`'s "Build & run" section carries any module-specific invocation
(e.g. suites needing a running Docker daemon for Testcontainers).

**Establish the baseline yourself.** Run the suite *before* you change anything and
record the counts from that run. That is your baseline. `CLAUDE.md`'s "Current
state" quotes a figure as of its last edit — treat it as a hint that may be stale,
never as the thing you compare against. When your work changes the count, update
that line as part of the commit.

## Traps

Tooling behaviours — these hold until the tool changes:

- `--tests "<pattern>"` reports **BUILD SUCCESSFUL while matching nothing**. Check
  the result count, not the exit status.
- Counting results: glob `*.xml`, not `TEST-*.xml`. On Windows Gradle shortens
  `@Nested` result filenames to `__TEST-<hash>...`, so the narrower glob silently
  omits any class that nests its `@Test` methods — and several here do.
- A test touching Adventure or Bukkit types needs `testRuntimeOnly` on `paper-api`
  in that module; `compileOnlyApi` is not on the test runtime classpath. It fails
  at *discovery*, so the symptom is a suite that never runs, not a red test.
- Library quirks are version-bound. Check the version in the build files before
  trusting a documented workaround — `CLAUDE.md`'s "Configuration" section notes
  the ones currently in force, such as the missing `Duration` serializer.

Repo conventions — verify against `CLAUDE.md` and the surrounding code, since these
are decisions that can be revisited:

- Config records: `@Required` alongside every `@NotNull` `@Setting` field.
- Schema changes: the migration script **and** its registration in the migrator's
  hardcoded step list. Adding only the file is the classic miss.
- Match the extension-point axis the code already uses rather than adding a branch
  to an existing class — new payload types, media, and categories each have their
  own registry.

## Executing a plan

Read the plan, raise any concern with the user before starting, then work tasks in
order, following each step as written. One task in progress at a time.

A plan written earlier may have gone stale — if a step names a file, type, or
command that no longer matches the tree, stop and reconcile it with the user rather
than forcing the step through.

**Stop and ask** on a blocker, an unclear step, a missing dependency, or a
verification that fails twice. Don't guess past it.

When every task is done, use the `ship` skill.

## When a test needs a live server

Some classes can only be exercised against a running Paper server — anything that
touches Bukkit's scheduler, the plugin lifecycle, or player-facing UI. The test
that decides this is *"can this run under JUnit without a server?"*, not membership
of a list; `CLAUDE.md`'s "Current state" names which classes qualify today, and
that set shrinks as testable seams are extracted.

For those only: say so up front, implement directly, verify by hand with
`runServer`, and report them as manually verified rather than covered. Everything
reachable from a test stays under the Iron Law.
