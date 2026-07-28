---
name: ship
description: Use before claiming work is done, fixed, or passing, and before committing in this plugin - verify with fresh output, self-review the diff, update CLAUDE.md, commit. Replaces superpowers verification-before-completion + requesting-code-review + receiving-code-review + finishing-a-development-branch.
---

# Ship

Evidence before assertions. Review your own diff — no reviewer subagent.

## 1. Verify

If you have not run the command **in this message**, you cannot claim it passes.

```
./gradlew build
./gradlew test
```

Read the counts and compare them against the baseline **you recorded before
starting** (see `implement`), not against a number written in any doc. Fewer tests
than you started with means a suite silently stopped running — confirm by counting
result files with a `*.xml` glob.

| Claim | Needs |
| --- | --- |
| tests pass | fresh output, 0 failures, count ≥ your baseline |
| builds | a full `./gradlew build` exit 0 — one module compiling is not enough |
| bug fixed | the reproducing test passes *and* you saw it fail before the fix |
| server-only code works | you actually drove it on `runServer` |
| plan complete | each task re-read against the diff |

"Should work now", "looks correct", "I'm confident" — run the command instead.

Some code is not reachable by `./gradlew test` at all — anything needing a live
Paper server (see `implement`, "When a test needs a live server"). Work out which
of your changes fall in that set *for the tree as it stands now*, then either drive
them on `runServer` or state plainly that they are unverified. Never let the suite
going green imply they were checked.

## 2. Self-review the diff

Read `git diff` yourself and check:

- **Correctness** — does each change do what the task asked, on the failure paths too?
- **Scope** — anything in the diff nobody asked for? Remove it.
- **Conventions** — does the change follow the patterns in the code around it, and
  the rules in `CLAUDE.md`? Extension points here are registries: prefer registering
  a new implementation over branching inside an existing one.
- **Completeness of a multi-part change** — several things in this repo require two
  edits to take effect (a migration script plus its registration, a payload type
  plus something to render or process it, a medium plus its sink). Adding the first
  half fails silently rather than loudly. Trace your change to the point where it
  actually runs.

Reach for `/code-review` when the change is large or genuinely security-sensitive.
Routine work does not need it.

## 3. Handling review feedback

Verify each point against the code before implementing it — external reviewers lack
this repo's context, and some apparent bugs are documented deliberate trade-offs
(`CLAUDE.md` "Current state" and the design docs' "Known limitations" sections list
the current ones). Push back with technical reasoning where they're wrong; fix and
say what changed where they're right. No "you're absolutely right", no thanks — the
diff is the acknowledgement. If any item is unclear, ask before implementing any of
them; they're often related.

## 4. Update the docs

`CLAUDE.md` is the map every future session starts from, so a stale line there
costs more than the change was worth. Update it in the same commit when your work
alters architecture, commands, config keys, schema, module layout, test counts, or
anything in "Current state" — including closing a gap it lists as open.

Same for these skills: if you hit a repo fact one of them states wrongly, fix it
here too.

## 5. Commit

Commit **directly to `main`** — no feature branch and no worktree unless the user
asks. Conventional prefix (`feat:`, `fix:`, `docs:`, `refactor:`), imperative
subject, body only when the why isn't obvious.

Include the `Co-Authored-By: Claude Opus 5` trailer. **Omit the `Claude-Session:`
trailer.**

Do not push or open a PR unless asked.
