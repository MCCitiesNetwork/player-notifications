# Config files are never rewritten Implementation Plan

**Goal:** The plugin copies a bundled config in when the file is absent and never writes to it again; a key the operator's file lacks is reported by a startup warning instead of being merged in silently.
**Spec:** `docs/superpowers/specs/2026-08-23-config-files-are-never-rewritten-design.md`

## Task 1: `ConfigKeyGaps`

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/config/ConfigKeyGaps.java`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/config/ConfigKeyGapsTest.java`

**Interfaces produced:**

```java
public final class ConfigKeyGaps {
    public static @NotNull List<String> missingKeys(@NotNull ConfigurationNode bundled,
                                                    @NotNull ConfigurationNode actual);
}
```

- [ ] Write the failing test:

```java
package io.github.md5sha256.playernotifications.paper.config;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigKeyGapsTest {

    private static ConfigurationNode yaml(String text) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .buildAndLoadString(text);
    }

    @Test
    void identicalTreesHaveNoGaps() throws ConfigurateException {
        String text = "a: 1\nb:\n  c: 2\n";
        assertEquals(List.of(), ConfigKeyGaps.missingKeys(yaml(text), yaml(text)));
    }

    @Test
    void reportsATopLevelKeyTheActualFileLacks() throws ConfigurateException {
        assertEquals(List.of("b"),
                ConfigKeyGaps.missingKeys(yaml("a: 1\nb: 2\n"), yaml("a: 1\n")));
    }

    @Test
    void reportsANestedKeyByItsDottedPath() throws ConfigurateException {
        assertEquals(List.of("categories.mail.label"),
                ConfigKeyGaps.missingKeys(
                        yaml("categories:\n  mail:\n    label: Mail\n    description: d\n"),
                        yaml("categories:\n  mail:\n    description: d\n")));
    }

    @Test
    void aDifferentValueIsAnOverrideNotAGap() throws ConfigurateException {
        assertEquals(List.of(),
                ConfigKeyGaps.missingKeys(yaml("a: 1\n"), yaml("a: 99\n")));
    }

    @Test
    void anOperatorAddedKeyIsNotReported() throws ConfigurateException {
        assertEquals(List.of(),
                ConfigKeyGaps.missingKeys(yaml("a: 1\n"), yaml("a: 1\nzz: 2\n")));
    }

    @Test
    void gapsAreSorted() throws ConfigurateException {
        assertEquals(List.of("a", "m", "z"),
                ConfigKeyGaps.missingKeys(yaml("z: 1\na: 1\nm: 1\n"), yaml("q: 1\n")));
    }

    @Test
    void anEmptyActualNodeReportsEveryBundledKey() throws ConfigurateException {
        assertEquals(List.of("a", "b.c"),
                ConfigKeyGaps.missingKeys(yaml("a: 1\nb:\n  c: 2\n"), yaml("{}\n")));
    }
}
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.config.ConfigKeyGapsTest"` — expect FAIL: `ConfigKeyGaps` does not exist.
- [ ] Implement `ConfigKeyGaps`: a private static `flatten(String path, ConfigurationNode node, Set<String> target)` mirroring `MessageContainer`'s flatten — a non-empty path whose node is neither a map nor empty is a leaf and is added; children recurse with `path + '.' + key`. `missingKeys` flattens both and returns `bundled.stream().filter(k -> !actual.contains(k)).sorted().toList()`. Private constructor; the class holds no state.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: Stop rewriting the four config files

**Files:** modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**No test.** Every line here needs a real data folder and a live server; the `implement` skill's
live-server exception applies. Manual verification is Task 4.

- [ ] In `copyDefaultsYaml`, delete the whole `try (InputStream defaultStream = getResource(fileName))`
      block — both the `existing.mergeFrom(...)` and the `loader.save(existing)` — and return `existing`
      straight after `loader.load()`. Update its javadoc: it now ensures the file exists (copying the
      bundled default on first run only), loads it, and returns the root node, writing nothing.
- [ ] Add a sibling that reads the bundled resource without touching disk:

```java
/**
 * Loads {@code <resourceName>.yml} from the plugin jar. Returns an empty node when the resource is
 * missing, so a packaging fault degrades to "no defaults to compare against" rather than an enable
 * failure -- the operator's own file has already loaded by every call site.
 */
private @NotNull ConfigurationNode bundledNode(@NotNull String resourceName) throws IOException {
    try (InputStream stream = getResource(resourceName + ".yml")) {
        if (stream == null) {
            getLogger().severe("Failed to find bundled default resource: " + resourceName + ".yml");
            return yamlLoader().build().createNode();
        }
        return yamlLoader()
                .source(() -> new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
                .build()
                .load();
    }
}
```

- [ ] Rewrite `reloadMessages` so bundled defaults sit **under** the operator's file, in memory only:

```java
/**
 * Reloads {@code messages.yml} into the existing container, with the bundled defaults underneath the
 * operator's file: a key they never overrode -- or one added by a later release -- resolves to the
 * shipped wording instead of rendering as its own name in chat. Nothing is written to disk.
 */
private void reloadMessages() throws IOException {
    ConfigurationNode merged = bundledNode("messages");
    // mergeFrom fills keys ABSENT from the receiver, so the receiver is the loser: merging the
    // operator's node into the bundled one is what makes the operator win. Reversing these two
    // silently makes every shipped default override the operator's edit.
    merged.mergeFrom(copyDefaultsYaml("messages"));
    this.messages.load(merged);
}
```

- [ ] Add the gap warning, covering the three read-only files (not `messages.yml`, which has no gaps by
      construction now):

```java
/**
 * Logs one warning per config file naming any key the bundled default has and the operator's file
 * lacks. This replaces what copy-defaults-then-merge used to do silently: without it, a key added in
 * a later release arrives as a primitive default of 0/false, or -- for a @Required reference key such
 * as settings.yml's default-media -- fails deserialization and disables the plugin, with nothing in
 * the log pointing at the cause. Comparison only; neither file is modified.
 */
private void warnAboutMissingConfigKeys() {
    for (String name : List.of("database", "settings", "categories")) {
        try {
            List<String> missing = ConfigKeyGaps.missingKeys(bundledNode(name), copyDefaultsYaml(name));
            if (!missing.isEmpty()) {
                getLogger().warning(name + ".yml is missing keys added by a newer version of the "
                        + "plugin; add them by hand (defaults are in the jar): " + missing);
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not check " + name + ".yml for missing keys.", ex);
        }
    }
}
```

- [ ] Call `warnAboutMissingConfigKeys()` from `onEnable` immediately after
      `warnAboutUnmappedCategoryTypes();`, and from `reload(CommandSender)` at the same point after its
      own `warnAboutUnmappedCategoryTypes();`.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: Correct the stale `PluginSettings` comment

**Files:** modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PluginSettings.java`

- [ ] Replace the comment above `deliver-on-join` — currently "Both keys are written into every data
      folder by the copy-defaults-then-merge path, including on upgrade", which is false after Task 2 —
      with: primitives, so deliberately not `@Required`; that rule exists to stop a missing key
      deserializing to null, which a primitive cannot do. A key absent from an older `settings.yml` is
      **not** written in on upgrade any more (see `warnAboutMissingConfigKeys`), so it deserializes to
      `0`/`false` and the compact constructor below is what makes that safe — a future boolean whose
      correct default is `true` must not rely on this.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect PASS (comment-only; confirms nothing else
      depended on it).
- [ ] Commit

## Task 4: Manual verification and `CLAUDE.md`

**Files:** modify `CLAUDE.md`

Live-server checklist — `./gradlew :platform:paper-plugin:runServer`, needs a reachable MariaDB:

- [ ] Delete `platform/paper-plugin/run/plugins/PlayerNotifications/` entirely and start the server.
      Confirm all four config files are created from the bundle and the plugin enables cleanly.
- [ ] Add a comment line and reorder two keys in `categories.yml`, and add a comment to `messages.yml`.
      Restart, run `/notifications reload`, restart again. Confirm **both files are byte-identical** to
      what you wrote — comments intact, order intact. This is the whole point of the change.
- [ ] Delete the `mail` category from `categories.yml`, reload, and confirm mail's data type now shows
      under "Other" in `/notifications preferences types` — it must **not** reappear in the file or in
      the dialog.
- [ ] Delete one key from `messages.yml` (`inbox.cleared-one` is a good one), reload, trigger it in
      game, and confirm the **shipped wording** appears — not the key name. Then edit a different key
      and confirm the edit wins over the bundled default.
- [ ] Confirm the startup log carries a warning naming the deleted `categories.yml` key from two steps
      above, and that it names the dotted path.
- [ ] Restore both files. Confirm a clean start logs no gap warning at all.
- [ ] With a category registered late (see the sibling defaults-dump plan, if that shipped), confirm a
      late-registration rebuild no longer touches `categories.yml` on disk.

- [ ] Update `CLAUDE.md`:
  - **"Configuration"** — replace the "copies bundled defaults into the data folder, merges in any new
    keys, and deserializes" sentence: the plugin copies a bundled default in only when the file is
    absent and never writes to a config file afterwards. Note `messages.yml`'s in-memory
    defaults-underlay and that the other three are read alone, with `warnAboutMissingConfigKeys`
    reporting the difference.
  - **"Messages"** — add that a key absent from the operator's file now falls back to the bundled
    wording rather than rendering as its own name, and that `MessageKeysTest`'s two-way walk is
    unchanged because it still checks the shipped resource.
  - **"Notification categories"** / **"Player commands"** — the `/notifications reload` description and
    `scheduleCategoryRebuild`'s "each rebuild re-reads and re-writes `categories.yml`" claim both need
    correcting; the rebuild only re-reads now. Fix the javadoc in
    `PlayerNotificationsPlugin.scheduleCategoryRebuild` in this task too — the coalescing is still
    worth having for the re-read and the dialog swap, so only the "re-writes" half is wrong.
  - **"Testing gotchas"** — bump the `:platform:paper-plugin:test` count and the total by the number of
    tests Task 1 adds, verified from a fresh `./gradlew test` run.
- [ ] Run `./gradlew build`
- [ ] Commit
