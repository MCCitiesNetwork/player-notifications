package io.github.md5sha256.playernotifications.paper.customtype;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CustomNotificationTypes}: what counts as a usable declaration, and the
 * warn-and-drop handling that keeps a hand-written mistake from costing the rest of the file.
 *
 * <p>The warnings are asserted on rather than eyeballed, because "the console names the key" is the
 * whole of the rejection decision — silently dropping a type an operator believes they declared is
 * the failure mode this class exists to avoid.
 */
class CustomNotificationTypesTest {

    /** MiniMessage throws on a legacy section sign, the mistake a hand-written config invites. */
    private static final String MALFORMED = "§cBroken";

    private Logger logger;
    private CapturingHandler handler;
    private CustomNotificationTypes types;

    @BeforeEach
    void setUp() {
        this.logger = Logger.getLogger("CustomNotificationTypesTest-" + System.nanoTime());
        this.logger.setUseParentHandlers(false);
        this.handler = new CapturingHandler();
        this.logger.addHandler(this.handler);
        this.types = new CustomNotificationTypes(this.logger);
    }

    /** Builds the root of a {@code notification-types.yml}: key → {@code {title, display-name}}. */
    private static ConfigurationNode node(Map<String, Map<String, String>> declarations) {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        declarations.forEach((key, fields) -> fields.forEach((field, value) -> {
            try {
                root.node(key).node(field).set(value);
            } catch (SerializationException ex) {
                throw new AssertionError(ex);
            }
        }));
        return root;
    }

    private static Map<String, Map<String, String>> declaration(String key, String title) {
        Map<String, Map<String, String>> declarations = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        declarations.put(key, fields);
        return declarations;
    }

    private void assertWarnedAbout(String key) {
        assertTrue(this.handler.records.stream()
                        .anyMatch(record -> record.getLevel() == Level.WARNING
                                && record.getMessage().contains(key)),
                "expected a WARNING naming '" + key + "', got: " + this.handler.messages());
    }

    @Test
    @DisplayName("a complete declaration is kept with both values")
    void completeDeclarationIsKept() {
        Map<String, Map<String, String>> declarations = declaration("restart-warning", "<red>Restart");
        declarations.get("restart-warning").put("display-name", "<red>Restart Warnings");

        this.types.load(node(declarations));

        DeclaredNotificationType declared = this.types.get("restart-warning").orElseThrow();
        assertEquals("restart-warning", declared.key());
        assertEquals("<red>Restart", declared.title());
        assertEquals("<red>Restart Warnings", declared.displayName());
        assertEquals(Set.of("restart-warning"), this.types.keys());
    }

    @Test
    @DisplayName("display-name is optional")
    void displayNameIsOptional() {
        this.types.load(node(declaration("restart-warning", "<red>Restart")));

        assertNull(this.types.get("restart-warning").orElseThrow().displayName());
        assertTrue(this.handler.records.isEmpty(), "an absent display name is not a mistake");
    }

    @Test
    @DisplayName("an absent title drops the type — there is nothing to render")
    void absentTitleDropsTheType() {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        assertDoesNotThrowOnSet(root, "restart-warning", "display-name", "Restart Warnings");

        this.types.load(root);

        assertEquals(Set.of(), this.types.keys());
        assertWarnedAbout("restart-warning");
    }

    @Test
    @DisplayName("a blank title drops the type")
    void blankTitleDropsTheType() {
        this.types.load(node(declaration("restart-warning", "   ")));

        assertEquals(Set.of(), this.types.keys());
        assertWarnedAbout("restart-warning");
    }

    @Test
    @DisplayName("a malformed title drops the type — there is no layer to fall through to")
    void malformedTitleDropsTheType() {
        this.types.load(node(declaration("restart-warning", MALFORMED)));

        assertEquals(Set.of(), this.types.keys());
        assertWarnedAbout("restart-warning");
    }

    @Test
    @DisplayName("a malformed display-name drops only the name, not the type")
    void malformedDisplayNameDropsOnlyTheName() {
        Map<String, Map<String, String>> declarations = declaration("restart-warning", "<red>Restart");
        declarations.get("restart-warning").put("display-name", MALFORMED);

        this.types.load(node(declarations));

        assertNull(this.types.get("restart-warning").orElseThrow().displayName());
        assertEquals("<red>Restart", this.types.title("restart-warning").orElseThrow());
        assertWarnedAbout("restart-warning");
    }

    @Test
    @DisplayName("a key longer than the dataType column drops the type")
    void overLongKeyIsDropped() {
        String sixtyFour = "a".repeat(64);
        String sixtyFive = "a".repeat(65);
        Map<String, Map<String, String>> declarations = declaration(sixtyFour, "<red>Fine");
        declarations.putAll(declaration(sixtyFive, "<red>Too long"));

        this.types.load(node(declarations));

        assertEquals(Set.of(sixtyFour), this.types.keys());
        assertWarnedAbout(sixtyFive);
    }

    @Test
    @DisplayName("a key outside [a-z0-9._-] drops the type")
    void illegalKeyIsDropped() {
        Map<String, Map<String, String>> declarations = new LinkedHashMap<>();
        declarations.putAll(declaration("Bad Key", "<red>x"));
        declarations.putAll(declaration("UPPER", "<red>x"));
        declarations.putAll(declaration("a<b>", "<red>x"));
        declarations.putAll(declaration("a.b-c_1", "<red>x"));

        this.types.load(node(declarations));

        assertEquals(Set.of("a.b-c_1"), this.types.keys());
        assertWarnedAbout("Bad Key");
        assertWarnedAbout("UPPER");
        assertWarnedAbout("a<b>");
    }

    @Test
    @DisplayName("a scalar where a map was expected is skipped without throwing")
    void scalarDeclarationIsSkipped() {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        try {
            root.node("restart-warning").set("just a string");
        } catch (SerializationException ex) {
            throw new AssertionError(ex);
        }

        this.types.load(root);

        assertEquals(Set.of(), this.types.keys());
        assertWarnedAbout("restart-warning");
    }

    @Test
    @DisplayName("reloading a file without a key removes it, and keeps the rest")
    void reloadDropsADeletedKey() {
        Map<String, Map<String, String>> both = new LinkedHashMap<>();
        both.putAll(declaration("restart-warning", "<red>Restart"));
        both.putAll(declaration("event", "<gold>Event"));
        this.types.load(node(both));
        assertEquals(Set.of("restart-warning", "event"), this.types.keys());

        this.types.load(node(declaration("event", "<gold>Event")));

        assertEquals(Set.of("event"), this.types.keys());
        assertTrue(this.types.get("restart-warning").isEmpty());
    }

    @Test
    @DisplayName("an empty file declares nothing and warns about nothing")
    void emptyFileIsSilent() {
        this.types.load(BasicConfigurationNode.root());

        assertEquals(Set.of(), this.types.keys());
        assertTrue(this.handler.records.isEmpty(), this.handler.messages());
    }

    private static void assertDoesNotThrowOnSet(ConfigurationNode root, String key, String field,
                                                String value) {
        try {
            root.node(key).node(field).set(value);
        } catch (SerializationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            this.records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String messages() {
            return this.records.stream().map(LogRecord::getMessage).toList().toString();
        }
    }
}
