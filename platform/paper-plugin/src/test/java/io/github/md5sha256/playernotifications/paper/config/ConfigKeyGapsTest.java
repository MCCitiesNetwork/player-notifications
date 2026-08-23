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

    /**
     * settings.yml's {@code default-media} is a list, so this is the real shape rather than a
     * contrived one. A list is a <em>leaf</em>: reported as one key when absent, and never expanded
     * into per-index paths such as {@code default-media.0}, which would make a shorter operator list
     * read as a set of missing keys and warn about an ordinary edit.
     */
    @Test
    void aListValuedKeyIsOneLeafNotOneKeyPerElement() throws ConfigurateException {
        assertEquals(List.of("default-media"),
                ConfigKeyGaps.missingKeys(
                        yaml("default-media:\n  - chat\n  - discord-dm\n"),
                        yaml("prune-interval-seconds: 3600\n")));
        assertEquals(List.of(),
                ConfigKeyGaps.missingKeys(
                        yaml("default-media:\n  - chat\n  - discord-dm\n"),
                        yaml("default-media:\n  - chat\n")));
    }
}
