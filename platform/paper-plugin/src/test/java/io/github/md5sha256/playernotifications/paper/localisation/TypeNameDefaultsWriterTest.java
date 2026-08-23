package io.github.md5sha256.playernotifications.paper.localisation;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeNameDefaultsWriterTest {

    private static final Logger LOGGER = Logger.getLogger(TypeNameDefaultsWriterTest.class.getName());

    private record Fixture(NotificationDataTypeRegistry registry, TypeNames typeNames) {
    }

    private static Fixture fixture() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        return new Fixture(registry, new TypeNames(registry, LOGGER));
    }

    /** Registers a payload mapping, which is what makes a data type "known" to the dump. */
    private static void knownType(NotificationDataTypeRegistry registry, String dataType) {
        registry.registerPayloadMapping(dataType, String.class);
    }

    private static ConfigurationNode reread(Path file) throws IOException {
        return YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK).path(file).build().load();
    }

    @Test
    void everyRegisteredTypeAppearsEvenWithNoModuleDefault(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture();
        knownType(fixture.registry(), "broadcast");
        knownType(fixture.registry(), "mail");
        fixture.registry().registerDisplayName("mail", "Personal Mail");

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        ConfigurationNode types = reread(file).node("types");
        assertEquals("Personal Mail", types.node("mail").getString());
        assertEquals("Broadcast", types.node("broadcast").getString());
    }

    @Test
    void aTypeWithOnlyADisplayNameAndNoPayloadMappingIsNotListed(@TempDir Path dir) throws IOException {
        // Mirrors NotificationDataTypeRegistry: naming a type does not make it a known type.
        Fixture fixture = fixture();
        knownType(fixture.registry(), "mail");
        fixture.registry().registerDisplayName("ghost", "Ghost");

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        assertTrue(reread(file).node("types").node("ghost").virtual());
    }

    @Test
    void entriesAreSorted(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture();
        knownType(fixture.registry(), "zeta");
        knownType(fixture.registry(), "alpha");
        knownType(fixture.registry(), "mid");

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        String text = Files.readString(file);
        assertTrue(text.indexOf("alpha:") < text.indexOf("mid:"), text);
        assertTrue(text.indexOf("mid:") < text.indexOf("zeta:"), text);
    }

    @Test
    void theHeaderSeparatesModuleSuppliedNamesFromTitleCasedFallbacks(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture();
        knownType(fixture.registry(), "mail");
        knownType(fixture.registry(), "broadcast");
        fixture.registry().registerDisplayName("mail", "Personal Mail");

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        String header = Files.readString(file).lines()
                .takeWhile(line -> line.startsWith("#"))
                .reduce("", (a, b) -> a + b + "\n");
        assertTrue(header.contains("Supplied by a module: mail"), header);
        assertTrue(header.contains("Title-cased fallback"), header);
        assertTrue(header.contains("broadcast"), header);
    }

    @Test
    void theHeaderNamesTypesTheOperatorHasAlreadyOverridden(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture();
        knownType(fixture.registry(), "mail");
        fixture.typeNames().load(YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .buildAndLoadString("mail: \"My Mail\"\n"));

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        assertTrue(Files.readString(file).contains("Already overridden in type-names.yml: mail"),
                Files.readString(file));
    }

    @Test
    void anOverrideDoesNotChangeTheDefaultThatIsListed(@TempDir Path dir) throws IOException {
        // The file is a record of the DEFAULTS. An operator's own value must not be echoed back as
        // though a module had supplied it, or the dump stops being a thing they can diff against.
        Fixture fixture = fixture();
        knownType(fixture.registry(), "mail");
        fixture.registry().registerDisplayName("mail", "Personal Mail");
        fixture.typeNames().load(YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .buildAndLoadString("mail: \"My Mail\"\n"));

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        assertEquals("Personal Mail", reread(file).node("types").node("mail").getString());
    }

    @Test
    void writingTwiceIsByteIdentical(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture();
        knownType(fixture.registry(), "mail");
        knownType(fixture.registry(), "broadcast");
        fixture.registry().registerDisplayName("mail", "Personal Mail");

        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        TypeNameDefaultsWriter writer =
                new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER);
        writer.write();
        String first = Files.readString(file);
        writer.write();
        assertEquals(first, Files.readString(file));
    }

    @Test
    void anEmptyRegistryStillWritesAReadableFile(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture();
        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();

        String text = Files.readString(file);
        assertTrue(text.startsWith("#"), text);
        assertTrue(text.contains("DO NOT EDIT"), text);
    }

    @Test
    void anUnwritablePathLogsAndDoesNotThrow(@TempDir Path dir) {
        Fixture fixture = fixture();
        Path file = dir.resolve(TypeNameDefaultsWriter.FILE_NAME);
        assertDoesNotThrow(() -> {
            Files.createDirectories(file);
            new TypeNameDefaultsWriter(file, fixture.registry(), fixture.typeNames(), LOGGER).write();
        });
    }
}
