package io.github.md5sha256.playernotifications.core.config;

import io.leangen.geantyref.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedYamlTest {

    private static final Logger LOGGER = Logger.getLogger(GeneratedYamlTest.class.getName());
    private static final TypeToken<Map<String, String>> MAP = new TypeToken<>() {
    };

    @Test
    void createsTheParentDirectoryWhenItDoesNotExist(@TempDir Path dir) throws IOException {
        // The generated files live in a `defaults` subfolder the plugin never otherwise creates, so
        // the first write on a fresh install has to make it.
        Path file = dir.resolve(GeneratedYaml.DIRECTORY_NAME).resolve("categories.yml");

        GeneratedYaml.write(file, "# header\n", "types", MAP, Map.of("mail", "Mail"), LOGGER);

        assertTrue(Files.exists(file), "expected the write to create " + file.getParent());
        assertTrue(Files.readString(file).contains("mail"), Files.readString(file));
    }

    @Test
    void writesIntoAnExistingDirectoryUnchanged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("categories.yml");

        GeneratedYaml.write(file, "# header\n", "types", MAP, Map.of("mail", "Mail"), LOGGER);

        assertTrue(Files.readString(file).startsWith("# header"), Files.readString(file));
    }

    @Test
    void anUnwritablePathLogsAndDoesNotThrow(@TempDir Path dir) {
        Path file = dir.resolve("categories.yml");
        assertDoesNotThrow(() -> {
            Files.createDirectories(file);
            GeneratedYaml.write(file, "# header\n", "types", MAP, Map.of(), LOGGER);
        });
    }
}
