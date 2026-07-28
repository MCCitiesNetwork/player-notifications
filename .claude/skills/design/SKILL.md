---
name: design
description: Use before writing code for any new feature, API change, or behaviour change in this plugin - covers clarify, agree an approach, write the spec, write the implementation plan. Replaces superpowers brainstorming + writing-plans.
---

# Design

Clarify → agree an approach → spec → plan. All inline, no subagents.

> Check the current shape of the code before leaning on anything here that
> describes it. The extension points, constraints, and doc layout below are as of
> this file's last edit; `CLAUDE.md` and the tree itself are authoritative.

## Gate

No implementation code until the user has approved a design. Scale the design to
the change: two sentences for a config key, a full spec for a new API surface.

## 1. Clarify

Read the relevant code first — `CLAUDE.md` describes the architecture, so don't
re-derive it, but do verify any specific claim you intend to build on. Then ask
questions **one at a time**, only where two readings lead to materially different
work. Prefer `AskUserQuestion` with concrete options.

Skip clarifying entirely when the ask is unambiguous.

If the request spans several independent subsystems, say so before refining
details — decompose it, then design the first piece.

## 2. Approach

Give **one recommendation** plus the one or two alternatives you actually
considered, each in a sentence with its trade-off. YAGNI hard.

Before proposing, work out which existing axis the change belongs on. This plugin
is built so that adding a capability means **registering an implementation**, not
branching inside an existing class — payload handling, delivery media, and
grouping each have their own registry, which is what keeps registrations additive
(N + M) rather than combinatorial (N × M). Find the registry that matches your
change and extend along it. Adding a new axis is itself a design decision worth
raising explicitly.

Then check the change against the constraints actually in force right now, rather
than assumed ones:

- **Data migration.** Whether existing rows must survive a schema change depends on
  whether the plugin is deployed anywhere yet. Ask rather than assume; the answer
  decides between collapsing migrations and layering a new one.
- **Compatibility.** Anything in the `api` module is consumed by feature modules
  compiled separately against it — breaking a signature there is a different
  decision from changing `core` internals.
- **Config and schema.** Check the conventions in `CLAUDE.md` ("Configuration",
  "Persistence layer") at design time, since a change that violates one needs
  designing around, not patching later.

## 3. Spec

On approval write a design doc under the specs directory the repo already uses
(currently `docs/superpowers/specs/`, named `YYYY-MM-DD-<topic>-design.md` — follow
whatever pattern the existing files show). Cover: goal, architecture, the types and
files touched, error handling, testing strategy, and a **Known limitations**
section naming what you deliberately did not solve.

Record *why* a trade-off was chosen, not just what it was. Those entries become the
answer when someone later asks whether a behaviour is a bug — and they should be
written so a future reader can tell when the reasoning has expired.

Self-check before showing it: no TBD/TODO, no two sections contradicting, no
requirement readable two ways. Fix inline, don't re-review.

Ask the user to review the spec file. Commit it on approval.

## 4. Plan

Write a plan alongside the spec, in the plans directory the repo uses (currently
`docs/superpowers/plans/`). A plan is tasks, each with an independently testable
deliverable and its own commit.

```markdown
# <Feature> Implementation Plan

**Goal:** one sentence
**Spec:** <path to the spec doc>

## Task N: <name>

**Files:** create / modify (`path:lines`) / test
**Interfaces:** exact signatures this task produces that later tasks call

- [ ] Write the failing test: <actual test code>
- [ ] Run `./gradlew :<module>:test --tests "<pattern>"` — expect FAIL: <reason>
- [ ] Implement: <actual code or precise description>
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit
```

Every task is test-first — see `implement` for the cycle and the exception for code
that needs a live server. Where a task lands in that exception, say so in the task
and give the manual verification steps instead of a test command.

No placeholders. "Add appropriate error handling", "similar to Task 2", or a step
with no command is a plan failure — write the real content.

Fold setup, config, and docs into the task whose deliverable needs them. Split only
where one task could be rejected while its neighbour stands. Include a final task
for updating `CLAUDE.md` if the change alters anything it documents.

Self-check: every spec requirement maps to a task; type and method names match
across tasks; each named file and command exists in the tree as it stands today.

Then execute it yourself with the `implement` skill. Don't dispatch a subagent per
task unless the user asks — coordination costs more context here than it saves.
