# Module category defaults Implementation Plan

**Goal:** Write a generated, never-read `categories-defaults.yml` snapshotting every category the code registry holds, so an operator can see what modules registered and reconcile it into `categories.yml` by hand.
**Spec:** `docs/superpowers/specs/2026-08-23-module-category-defaults-design.md`

## Task 1: `CategoryDefaultsWriter` in `core`

**Files:**
- create `core/src/main/java/io/github/md5sha256/playernotifications/core/category/CategoryDefaultsWriter.java`
- create `core/src/test/java/io/github/md5sha256/playernotifications/core/category/CategoryDefaultsWriterTest.java`

**Interfaces produced:**

```java
public final class CategoryDefaultsWriter {
    public static final String FILE_NAME = "categories-defaults.yml";
    public CategoryDefaultsWriter(@NotNull Path file, @NotNull Logger logger);
    public void write(@NotNull NotificationCategoryRegistry registry);
    static @NotNull Map<String, NotificationCategoryDefinition> snapshot(@NotNull NotificationCategoryRegistry registry);
}
```

- [ ] Write the failing test:

```java
package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CategoryDefaultsWriterTest {

    private static final Logger LOGGER = Logger.getLogger(CategoryDefaultsWriterTest.class.getName());

    @Test
    void writesRegisteredCategories(@TempDir Path dir) throws IOException {
        NotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("economy", "Economy", "Shop sales and payments");
        registry.claimDataType("economy", "receipt");
        registry.claimDataType("economy", "payment");

        Path file = dir.resolve(CategoryDefaultsWriter.FILE_NAME);
        new CategoryDefaultsWriter(file, LOGGER).write(registry);

        Map<String, NotificationCategoryDefinition> snapshot = CategoryDefaultsWriter.snapshot(registry);
        assertEquals(
                new NotificationCategoryDefinition("Economy", "Shop sales and payments",
                        List.of("payment", "receipt")),
                snapshot.get("economy"));
        assertTrue(Files.readString(file).contains("label: \"Economy\"")
                || Files.readString(file).contains("label: Economy"));
    }

    @Test
    void emptyRegistryWritesEmptyCategories(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(CategoryDefaultsWriter.FILE_NAME);
        new CategoryDefaultsWriter(file, LOGGER).write(new DefaultNotificationCategoryRegistry());
        assertTrue(Files.exists(file));
        assertTrue(CategoryDefaultsWriter.snapshot(new DefaultNotificationCategoryRegistry()).isEmpty());
    }

    @Test
    void implicitCategoryKeepsEmptyLabelAndDescription() {
        NotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.claimDataType("shop", "receipt");
        assertEquals(new NotificationCategoryDefinition("", "", List.of("receipt")),
                CategoryDefaultsWriter.snapshot(registry).get("shop"));
    }

    @Test
    void keysAndTypesAreSorted(@TempDir Path dir) throws IOException {
        NotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("zeta", "Zeta", "");
        registry.registerCategory("alpha", "Alpha", "");
        registry.claimDataType("alpha", "gamma");
        registry.claimDataType("alpha", "beta");

        Path file = dir.resolve(CategoryDefaultsWriter.FILE_NAME);
        new CategoryDefaultsWriter(file, LOGGER).write(registry);

        String text = Files.readString(file);
        assertTrue(text.indexOf("alpha:") < text.indexOf("zeta:"), text);
        assertTrue(text.indexOf("beta") < text.indexOf("gamma"), text);
    }

    @Test
    void writingTwiceIsByteIdentical(@TempDir Path dir) throws IOException {
        NotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("economy", "Economy", "d");
        registry.claimDataType("economy", "receipt");
        registry.claimDataType("economy", "payment");

        Path file = dir.resolve(CategoryDefaultsWriter.FILE_NAME);
        CategoryDefaultsWriter writer = new CategoryDefaultsWriter(file, LOGGER);
        writer.write(registry);
        String first = Files.readString(file);
        writer.write(registry);
        assertEquals(first, Files.readString(file));
    }

    @Test
    void startsWithAGeneratedHeaderComment(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(CategoryDefaultsWriter.FILE_NAME);
        new CategoryDefaultsWriter(file, LOGGER).write(new DefaultNotificationCategoryRegistry());
        String text = Files.readString(file);
        assertTrue(text.startsWith("#"), text);
        assertTrue(text.contains("DO NOT EDIT"), text);
    }

    @Test
    void anUnwritablePathLogsAndDoesNotThrow(@TempDir Path dir) {
        // A directory where the file should be: writeString fails with IOException.
        Path file = dir.resolve(CategoryDefaultsWriter.FILE_NAME);
        assertDoesNotThrow(() -> {
            Files.createDirectories(file);
            new CategoryDefaultsWriter(file, LOGGER).write(new DefaultNotificationCategoryRegistry());
        });
    }
}
```

- [ ] Run `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.category.CategoryDefaultsWriterTest"` — expect FAIL: `CategoryDefaultsWriter` does not exist.

- [ ] Implement `CategoryDefaultsWriter`:
  - `snapshot(registry)` builds a `TreeMap<String, NotificationCategoryDefinition>` over
    `registry.categoryKeys()`, each value `new NotificationCategoryDefinition(registry.label(key),
    registry.description(key), registry.dataTypesFor(key).stream().sorted().toList())`.
  - `write(registry)` renders `snapshot` into a string:
    `StringWriter out; YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
    .nodeStyle(NodeStyle.BLOCK).sink(() -> new BufferedWriter(out)).build();
    ConfigurationNode root = loader.createNode();
    root.node("categories").set(new TypeToken<Map<String, NotificationCategoryDefinition>>() {}, snapshot(registry));
    loader.save(root);`
    then `Files.writeString(this.file, HEADER + out)`.
  - `HEADER` is a text block of `#` lines: names the file as generated and never read, says
    `categories.yml` is the live file, says a block is copied across by hand, and explains that a
    blank `label` means a module claimed data types without naming its category.
  - `write` wraps everything in `try { ... } catch (IOException ex) { logger.log(Level.WARNING,
    "Failed to write " + this.file, ex); }` — and also catches `ConfigurateException`, which is an
    `IOException` subtype, so the one catch covers the `set`/`save` calls too.

- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: Wire it into `PlayerNotificationsPlugin`

**Files:** modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**No test.** Both call sites need a live server (`getDataFolder()`, the Bukkit scheduler, module
startup), which is the `implement` skill's live-server exception. Manual verification is Task 3.

- [ ] Add a field `private CategoryDefaultsWriter categoryDefaultsWriter;` and, in `onEnable` immediately
      before `this.categories = loadCategories();`, assign
      `this.categoryDefaultsWriter = new CategoryDefaultsWriter(
          getDataFolder().toPath().resolve(CategoryDefaultsWriter.FILE_NAME), getLogger());`
      (after the data folder is known to exist — `copyDefaultsYaml` inside `loadCategories()` creates it,
      and `CategoryDefaultsWriter` is not used until after that call returns).
- [ ] In `rebuildCategories(String when)`, after `this.preferenceDialogRouter.reloadCategories(this.categories);`,
      add `this.categoryDefaultsWriter.write(this.notificationService.categoryRegistry());`
- [ ] In `reload(CommandSender sender)`, after `this.preferenceDialogRouter.reloadCategories(newCategories);`,
      add the same line.
- [ ] At the end of `onEnable` (after `warnAboutUnmappedCategoryTypes();`), schedule the one-tick
      backstop write:

```java
// One tick: runs on the server's first tick, after every other plugin's onEnable has returned.
// The change listener is not enough on its own -- NotificationCategoryRegistry#addChangeListener
// has a default no-op body, so a third-party registry implementation never notifies us at all.
getServer().getScheduler().runTask(this, () -> {
    if (!isEnabled()) {
        return;
    }
    this.categoryDefaultsWriter.write(this.notificationService.categoryRegistry());
});
```
- [ ] In `onDisable`, null the field alongside `this.categories = null;`
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: Manual verification and `CLAUDE.md`

**Files:** modify `CLAUDE.md`

Live-server checklist — `./gradlew :platform:paper-plugin:runServer`, needs a reachable MariaDB:

- [ ] Start the server. Confirm `platform/paper-plugin/run/plugins/PlayerNotifications/categories-defaults.yml`
      exists after enable, starts with the `#` header, and holds an empty `categories` node — the stock
      install has no code-registered category.
- [ ] Delete `categories-defaults.yml` while the server is **stopped**, start it, and confirm the file
      is back — proving the one-tick backstop wrote it, since nothing else runs on a clean start with
      no code-registered category.
- [ ] Confirm `categories.yml` is untouched and `/notifications preferences types` still shows
      Diagnostics, Mail, Broadcasts and Other exactly as before.
- [ ] Delete `categories-defaults.yml`, run `/notifications reload`, confirm it is recreated and the
      reload reports success.
- [ ] Make the file read-only (or replace it with a directory), run `/notifications reload`: expect a
      single `WARNING` naming the path in the console and a **successful** reload reply to the player.
- [ ] Register a category from a throwaway module (or temporarily call
      `notificationService.categoryRegistry().registerCategory("economy", "Economy", "d")` plus two
      `claimDataType` calls from `onEnable` after `startModules()`): confirm the file gains the block,
      and the keys and types are sorted.
- [ ] With that temporary registration still in place, add a temporary log line at the top of `write`
      and restart. Expect **at most two** writes: one from the coalesced rebuild (three registrations
      must not produce three writes — that is the `scheduleCategoryRebuild` guard doing its job) and
      one from the one-tick backstop. Confirm the two produce identical file content, then remove the
      log line and the temporary registration.
- [ ] Copy that block into `categories.yml`, reload, confirm the category appears in
      `/notifications preferences types` and that `categories-defaults.yml` still holds its own copy
      unchanged — the two files are independent.

- [ ] Update `CLAUDE.md`:
  - **"Configuration"** — add `categories-defaults.yml` to the list, marked generated and never read,
    naming `CategoryDefaultsWriter` and the fact that `copyDefaultsYaml`'s copy-then-merge idiom does
    not apply to it.
  - **"Notification categories"** — add a paragraph: the code registry is still a live merge input
    (unchanged), *and* is separately dumped to `categories-defaults.yml` for the operator to reconcile
    by hand; the dump is one-way, so a copied block does not track the module's later rewording.
  - **"Current state"** — amend the `NotificationCategoryRegistry has no in-tree consumer` bullet to
    note that the defaults dump is therefore empty on a stock install and exercised only by unit tests.
  - **"Testing gotchas"** — bump the `:core:test` count and the total by the number of tests Task 1 adds
    (verified from a fresh `./gradlew test` run, not estimated).
- [ ] Run `./gradlew build`
- [ ] Commit
