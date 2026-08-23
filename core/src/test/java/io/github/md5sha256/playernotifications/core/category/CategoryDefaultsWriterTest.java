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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
