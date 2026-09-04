package io.github.md5sha256.playernotifications.paper.config;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeliveryDefaults#load}'s rules. The warnings are asserted as well as the map, because a
 * rejected entry that says nothing in the console is indistinguishable from a typo nobody noticed.
 */
class DeliveryDefaultsTest {

    private final List<LogRecord> warnings = new ArrayList<>();

    private static ConfigurationNode yaml(String text) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .buildAndLoadString(text);
    }

    private Logger recordingLogger() {
        Logger logger = Logger.getLogger("DeliveryDefaultsTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private Map<String, Set<String>> load(String text) throws ConfigurateException {
        return DeliveryDefaults.load(yaml(text), recordingLogger());
    }

    private void assertWarnedAbout(String key) {
        assertEquals(1, this.warnings.size(), "expected exactly one warning");
        assertTrue(this.warnings.get(0).getMessage().contains(key),
                "warning should name '" + key + "': " + this.warnings.get(0).getMessage());
    }

    @Test
    void readsAListOfMedia() throws ConfigurateException {
        assertEquals(Map.of("restart-warning", Set.of("chat", "discord-dm")),
                load("restart-warning:\n  - chat\n  - discord-dm\n"));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void duplicateMediaCollapse() throws ConfigurateException {
        assertEquals(Map.of("restart-warning", Set.of("chat")),
                load("restart-warning:\n  - chat\n  - chat\n"));
    }

    @Test
    void noneIsAcceptedSoATypeCanBeOptIn() throws ConfigurateException {
        assertEquals(Map.of("maintenance", Set.of("none")), load("maintenance:\n  - none\n"));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void theBlanketKeyIsRejected() throws ConfigurateException {
        assertEquals(Map.of(), load("'*':\n  - chat\n"));
        assertWarnedAbout("*");
    }

    @Test
    void aScalarValueIsRejected() throws ConfigurateException {
        assertEquals(Map.of(), load("broadcast: chat\n"));
        assertWarnedAbout("broadcast");
    }

    @Test
    void aNestedMapIsRejected() throws ConfigurateException {
        assertEquals(Map.of(), load("broadcast:\n  nested: chat\n"));
        assertWarnedAbout("broadcast");
    }

    @Test
    void anEmptyListIsRejected() throws ConfigurateException {
        assertEquals(Map.of(), load("broadcast: []\n"));
        assertWarnedAbout("broadcast");
    }

    @Test
    void blankEntriesAreDroppedButTheEntrySurvives() throws ConfigurateException {
        assertEquals(Map.of("broadcast", Set.of("chat")), load("broadcast:\n  - ''\n  - chat\n"));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void anEntryOfOnlyBlanksIsRejected() throws ConfigurateException {
        assertEquals(Map.of(), load("broadcast:\n  - ''\n"));
        assertWarnedAbout("broadcast");
    }

    @Test
    void oneBadEntryDoesNotCostTheGoodOnes() throws ConfigurateException {
        assertEquals(Map.of("mail", Set.of("discord-dm")),
                load("broadcast: chat\nmail:\n  - discord-dm\n"));
        assertWarnedAbout("broadcast");
    }

    @Test
    void anEmptyDocumentIsAnEmptyMap() throws ConfigurateException {
        assertEquals(Map.of(), load(""));
        assertEquals(List.of(), this.warnings);
    }
}
