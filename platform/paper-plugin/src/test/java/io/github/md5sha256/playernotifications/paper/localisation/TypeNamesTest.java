package io.github.md5sha256.playernotifications.paper.localisation;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TypeNames}: the three-layer resolution chain, and the warn-once error
 * handling that keeps a bad value out of the preference screens without spamming the console.
 *
 * <p>Logging is asserted on rather than eyeballed, because "warns exactly once" is the whole of the
 * malformed-value decision — a test that only checked the fall-through would pass just as happily
 * against an implementation that reprinted the warning on every render.
 */
class TypeNamesTest {

    /**
     * The malformed-value fixture. MiniMessage throws a {@code ParsingException} on a legacy section
     * sign, which is exactly the mistake a hand-written config invites.
     */
    private static final String MALFORMED = "§cBroken";

    private NotificationDataTypeRegistry registry;
    private Logger logger;
    private CapturingHandler handler;
    private TypeNames typeNames;

    @BeforeEach
    void setUp() {
        this.registry = new NotificationDataTypeRegistry();
        this.logger = Logger.getLogger("TypeNamesTest-" + System.nanoTime());
        this.logger.setUseParentHandlers(false);
        this.handler = new CapturingHandler();
        this.logger.addHandler(this.handler);
        this.typeNames = new TypeNames(this.registry, this.logger);
    }

    private static ConfigurationNode node(Map<String, ?> values) {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        values.forEach((key, value) -> {
            try {
                if (value instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> {
                        try {
                            root.node(key).node(String.valueOf(k)).set(v);
                        } catch (SerializationException ex) {
                            throw new AssertionError(ex);
                        }
                    });
                } else {
                    root.node(key).set(value);
                }
            } catch (SerializationException ex) {
                throw new AssertionError(ex);
            }
        });
        return root;
    }

    private String plain(String dataType) {
        return PlainTextComponentSerializer.plainText().serialize(this.typeNames.name(dataType));
    }

    @Test
    void unconfiguredTypeIsTitleCased() {
        assertEquals("Essentials Mail", plain("essentials_mail"));
        assertEquals("Essentials Mail", plain("essentials-mail"));
        assertEquals("Mail", this.typeNames.plainName("mail"));
    }

    @Test
    void moduleDefaultIsUsedWhenNoOverride() {
        this.registry.registerDisplayName("mail", "Personal Post");
        assertEquals("Personal Post", this.typeNames.plainName("mail"));
    }

    @Test
    void operatorOverrideBeatsModuleDefault() {
        this.registry.registerDisplayName("mail", "Personal Post");
        this.typeNames.load(node(Map.of("mail", "Operator Mail")));
        assertEquals("Operator Mail", this.typeNames.plainName("mail"));
        assertEquals(Optional.of("Operator Mail"), this.typeNames.override("mail"));
        assertEquals(Optional.empty(), this.typeNames.override("broadcast"));
    }

    @Test
    void miniMessageFormattingSurvivesInName() {
        this.typeNames.load(node(Map.of("mail", "<gold>Personal Mail</gold>")));
        assertEquals("Personal Mail", plain("mail"));
        assertFalse(this.typeNames.name("mail").style().isEmpty(),
                "the gold colour should survive into the rendered component");
    }

    @Test
    void plainNameFlattensIt() {
        this.typeNames.load(node(Map.of("mail", "<gold>Personal Mail</gold>")));
        assertEquals("Personal Mail", this.typeNames.plainName("mail"));
    }

    @Test
    void malformedOverrideFallsThroughToModuleDefault() {
        this.registry.registerDisplayName("mail", "Personal Post");
        this.typeNames.load(node(Map.of("mail", MALFORMED)));
        assertEquals("Personal Post", this.typeNames.plainName("mail"));
    }

    @Test
    void malformedOverrideLogsOneWarningAndIsDropped() {
        this.typeNames.load(node(Map.of("mail", MALFORMED)));
        assertEquals(1, this.handler.records.size());
        assertTrue(this.handler.records.getFirst().getMessage().contains("mail"),
                "the warning must name the offending key");
        assertEquals(Optional.empty(), this.typeNames.override("mail"),
                "an unparseable override is dropped, not stored");
        this.typeNames.name("mail");
        this.typeNames.name("mail");
        assertEquals(1, this.handler.records.size(),
                "rendering must not re-parse and re-warn about a dropped override");
        assertEquals("Mail", this.typeNames.plainName("mail"));
    }

    @Test
    void malformedModuleDefaultWarnsOncePerDataType() {
        this.registry.registerDisplayName("mail", MALFORMED);
        assertEquals("Mail", this.typeNames.plainName("mail"));
        this.typeNames.name("mail");
        this.typeNames.name("mail");
        assertEquals(1, this.handler.records.size());
        assertTrue(this.handler.records.getFirst().getMessage().contains("mail"));

        this.registry.registerDisplayName("broadcast", MALFORMED);
        assertEquals("Broadcast", this.typeNames.plainName("broadcast"));
        assertEquals(2, this.handler.records.size(), "the warning is once per data type, not once ever");
    }

    @Test
    void loadClearsTheWarnedOnceSet() {
        this.registry.registerDisplayName("mail", MALFORMED);
        this.typeNames.name("mail");
        assertEquals(1, this.handler.records.size());
        this.typeNames.load(BasicConfigurationNode.root());
        this.typeNames.name("mail");
        assertEquals(2, this.handler.records.size(),
                "a reload must re-report a module default that is still broken");
    }

    @Test
    void blankValueFallsThroughSilently() {
        this.registry.registerDisplayName("mail", "   ");
        this.typeNames.load(node(Map.of("mail", "  ")));
        assertEquals("Mail", this.typeNames.plainName("mail"));
        assertEquals(Optional.empty(), this.typeNames.override("mail"));
        assertTrue(this.handler.records.isEmpty(), "a blank is a deletion, not a mistake");
    }

    @Test
    void loadReplacesThePreviousMap() {
        this.typeNames.load(node(Map.of("mail", "Operator Mail", "broadcast", "Shouts")));
        assertEquals("Operator Mail", this.typeNames.plainName("mail"));
        this.typeNames.load(node(Map.of("broadcast", "Shouts")));
        assertEquals("Mail", this.typeNames.plainName("mail"), "a key absent from the new node stops overriding");
        assertEquals("Shouts", this.typeNames.plainName("broadcast"));
    }

    @Test
    void nonScalarNodeValueIsSkipped() {
        this.typeNames.load(node(Map.of("mail", Map.of("nested", "Value"), "broadcast", "Shouts")));
        assertEquals("Mail", this.typeNames.plainName("mail"));
        assertEquals("Shouts", this.typeNames.plainName("broadcast"));
        assertTrue(this.handler.records.isEmpty(),
                "a structurally odd node costs one wrong label, not a warning or a failed reload");
    }

    @Test
    void titleCaseHandlesSeparatorsAndCasing() {
        assertEquals("Discord Dm", TypeNames.titleCase("discord-dm"));
        assertEquals("Mail", TypeNames.titleCase("MAIL"));
        assertEquals("", TypeNames.titleCase(""));
    }

    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                this.records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
