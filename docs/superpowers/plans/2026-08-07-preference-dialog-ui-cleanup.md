# Preference dialog UI cleanup

## Context

Players reported two problems with `/notifications preferences`:

1. **Apply is only on a screen they already left.** `Apply (N changed)` and `Discard changes` exist
   solely on `PreferenceRootDialog` (`PreferenceRootDialog.java:74-87`), and only when the session is
   dirty. Every editor's *Save* writes into the `PreferenceEditSession` and navigates back to its
   picker (`MediumEditorDialog.java:58`, `CategoryEditorDialog.java:78`) — so a player who presses
   Save and closes the dialog loses everything, with nothing on screen saying so.
2. **The vocabulary is opaque.** "delivery method", "media", "(mixed)" and the raw data-type keys in
   checkbox rows (`"Mail: essentials-mail"`, `PreferenceDialogs.java:121`) do not mean anything to a
   player.

**Decisions taken with the user:**

- **Keep staging.** Changes stay staged; Apply/Discard become reachable from *every* screen, and each
  screen says how many unsaved changes are pending. The two-step model survives — it just stops being
  hidden.
- **Leave "Mute everything", "Reset all to server default" and "Use server default" exactly as they
  are** — both label and behaviour. The renaming is limited to the pivot labels, the body text, and
  the checkbox rows.

Outcome: a player can save from wherever they are, and can see at all times that something is
unsaved.

---

## Scope

All changes are in `platform/paper-plugin/src/main/java/.../paper/preferences/`, plus one test file
and the docs.

### Task 1 — Shared "staged changes" affordance

**Files:** modify `PreferenceDialogs.java`; test `PreferenceDialogsTest.java`.

Add two package-private helpers so all five screens render the same thing rather than five copies:

- `static Optional<Component> stagedSummary(PreferenceEditSession session)` — `Optional.empty()` when
  clean, else a component like `"You have 3 unsaved changes. Press Apply to save them."` in YELLOW.
  Pure and unit-testable (no Bukkit).
- `static void addStagedButtons(PreferenceDialogRouter router, Player player,
  PreferenceEditSession session, List<ActionButton> buttons, Runnable onCommitted, Runnable
  beforeApply)` — appends `Apply (N changed)` and `Discard changes` when `session.isDirty()`.
  `beforeApply` is how an editor folds its own checkbox state in before applying (see Task 3);
  pickers/root pass `() -> {}`. `onCommitted` is the screen to reopen afterwards.

Body text on each screen becomes `DialogBody.plainMessage(INTRO)` plus, when present, the staged
summary — the existing single-element `List.of(...)` bodies become a built list.

- [ ] Add to `PreferenceDialogsTest`: `stagedSummary` is empty for a clean session, and names the
      count for a dirty one (build a `PreferenceEditSession` directly, as
      `PreferenceEditSessionTest` already does).
- [ ] `./gradlew :platform:paper-plugin:test --tests "*PreferenceDialogsTest"` — expect FAIL (no such
      method), then PASS after implementing.

### Task 2 — Apply/Discard on the picker and root screens

**Files:** modify `PreferenceDialogRouter.java`, `PreferenceRootDialog.java`,
`MediumPickerDialog.java`, `CategoryPickerDialog.java`.

- `PreferenceDialogRouter.apply` (`:126`) gains a `Runnable onSaved` parameter, run on the main
  thread after `sessions.drop(uuid)` succeeds. Callers pass the *reloading* entry point for their own
  screen (`openRoot` / `openMediaPicker` / `openCategoryPicker`), since the session object they hold
  is dead once dropped. Existing behaviour on failure is unchanged (session survives, RED message).
- Discard everywhere: drop the session, then reopen the same screen via its reloading entry point —
  today the root's Discard just closes the dialog (`PreferenceRootDialog.java:80-86`), which reads as
  "did that do anything?".
- Root: replace its inline Apply/Discard block with `addStagedButtons`; keep `Mute everything` and
  `Reset all to server default` untouched, staged, re-showing the root as they do now.
- Both pickers: append `addStagedButtons` after the per-row buttons. Both are `columns(1)`, so this
  is purely additive.

Not covered by automated tests — dialogs need a live server (see `CLAUDE.md`). Manual steps in the
verification section below.

### Task 3 — Apply/Discard on the two editors

**Files:** modify `MediumEditorDialog.java`, `CategoryEditorDialog.java`.

The wrinkle: an editor's checkbox state lives in the dialog response, not the session, so a bare
Apply pressed there would silently discard the edits on screen. So on editors, **Apply first runs the
same session writes Save does**, via the `beforeApply` hook from Task 1:

- `MediumEditorDialog`: extract the Save body (`:53-57`) into a `commit(response)` lambda; Save =
  `commit` then `showMediaPicker`; Apply = `commit` then `router.apply(..., router::openMediaPicker)`.
- `CategoryEditorDialog`: same, extracting `:67-77`. `Use server default` (`:81-89`) stays exactly as
  it is — staged, returning to the picker.

Both are `columns(2)`; the medium editor goes to 3 buttons (Save, Apply, Discard) and the category
editor to 4 (Save, Use server default, Apply, Discard). `List.of(save)` /
`List.of(save, useDefault)` become mutable lists.

### Task 4 — Relabelling

**Files:** modify all five dialog classes and `PreferenceDialogs.java`; test
`PreferenceDialogsTest.java`.

| Where | Now | Becomes |
|---|---|---|
| `PreferenceRootDialog:31` | `By delivery method` | `Delivery methods` |
| `PreferenceRootDialog:32` | `By notification type` | `Notification types` |
| `PreferenceRootDialog:28-30` INTRO | "Manage notifications by delivery method or by notification type. Changes are staged until you press Apply." | `Choose how notifications reach you. Nothing is saved until you press Apply.` |
| `MediumPickerDialog:25` TITLE | `By Delivery Method` | `Delivery Methods` |
| `MediumPickerDialog:26-27` INTRO | "Choose a delivery method to configure which notifications reach you there." | `Pick a delivery method to choose which notifications are sent to it.` |
| `CategoryPickerDialog:27` TITLE | `By Notification Type` | `Notification Types` |
| `CategoryPickerDialog:28-29` INTRO | "Choose a notification type to configure where it reaches you." | `Pick a kind of notification to choose where it is sent.` |
| `MediumEditorDialog:67-68` body | "Choose which notifications reach you through this delivery method." | `Choose which notifications are sent here.` |
| `CategoryEditorDialog:96-97` body | "Choose where this kind of notification reaches you." | `Choose where this kind of notification is sent.` |
| `CategoryEditorDialog:37` | `" (mixed)"` | `" (partly on)"` |
| `PreferenceDialogs:119-122` `dataTypeLabel` | `"Mail: essentials-mail"` | `"Mail: Essentials Mail"` |

**Unchanged, by the user's instruction:** `Mute everything`, `Reset all to server default`,
`Use server default`, and `MediumPickerDialog`'s `" (server default)"` suffix.

`dataTypeLabel` title-cases the raw key with the same rule already used twice in `api` —
`NotificationSink#displayName` and `AccountLinkProvider.titleCase`
(`api/.../link/AccountLinkProvider.java:42-60`, whose javadoc records that the duplication is
deliberate rather than worth a public helper). Copy the same private helper into `PreferenceDialogs`
for consistency with that precedent.

- [ ] Add to `PreferenceDialogsTest`: `dataTypeLabel` renders `"Mail: Essentials Mail"` for
      `essentials-mail` under a category labelled `Mail`; a single-word key like `test` renders
      `Test`.
- [ ] `./gradlew :platform:paper-plugin:test` — expect PASS.

### Task 5 — Docs

**Files:** `docs/USAGE.md`, `CLAUDE.md`.

- `docs/USAGE.md`: the "Setting your preferences" section (`:54-74`) names the old labels and states
  that Apply lives on the root screen. Rewrite for the new labels and for Apply/Discard being on
  every screen. The command table rows for `preferences media` / `preferences types` (`:22-23`) quote
  the old pivot names.
- `CLAUDE.md`: the "Player commands" section describes `PreferenceRootDialog` as the only place
  Apply/Discard appear, and quotes the pivot labels. Update those, and the note about the medium
  editor's rows being labelled by raw data type.

- [ ] `./gradlew build -x :core:test -x :platform:discord-adapter:test`
- [ ] Commit.

---

## Verification

Automated: `./gradlew :platform:paper-plugin:test` (currently 41 tests; Tasks 1 and 4 add to
`PreferenceDialogsTest`). Then `./gradlew build` — Docker must be running for `:core:test` and
`:platform:discord-adapter:test`.

Manual, via `./gradlew :platform:paper-plugin:runServer` — the dialog layer has no automated coverage
at all, so this is the real check:

1. `/notifications preferences` → root shows `Delivery methods` / `Notification types`, no staged
   summary, no Apply/Discard.
2. `Delivery methods` → `Chat` → untick a notification → **Save**. The picker now shows the staged
   summary line and an `Apply (1 changed)` button.
3. Press **Apply** from the picker. Chat says "Notification preferences saved."; the picker reopens
   with no staged summary.
4. Repeat step 2, then press **Apply** *from inside the editor* — the checkbox change made on that
   screen must be included in what is saved (this is the `beforeApply` path).
5. **Discard** from a picker: staged summary and buttons disappear, screen reopens showing the
   previously saved state.
6. `Mute everything` and `Reset all to server default` on the root still stage rather than write, and
   `/notifications preferences mute` still writes immediately with its "unsaved changes were
   discarded" notice.
7. Checkbox rows read `Mail: Essentials Mail`, not `Mail: essentials-mail`; a category with disagreeing
   members shows `(partly on)`.
