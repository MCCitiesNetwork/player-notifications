package io.github.md5sha256.playernotifications.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class ModuleConfigsTest {

    private static ModuleConfigs.ResourceSupplier bundled(String yaml) {
        return () -> new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theBundledDefaultIsWrittenOnFirstRun(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("modules").resolve("discord.yml");

        ConfigurationNode root = ModuleConfigs.load(file, bundled("bot-token: \"\"\nembed-color: \"#5865F2\"\n"));

        Assertions.assertTrue(Files.exists(file), "the default should have been copied out");
        Assertions.assertEquals("#5865F2", root.node("embed-color").getString());
    }

    @Test
    void anExistingValueSurvivesAndNewDefaultKeysAreMergedIn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discord.yml");
        Files.writeString(file, "bot-token: \"mine\"\n");

        ConfigurationNode root = ModuleConfigs.load(
                file, bundled("bot-token: \"\"\nmessage-format: embed\n"));

        Assertions.assertEquals("mine", root.node("bot-token").getString(),
                "an operator's value must not be overwritten by the default");
        Assertions.assertEquals("embed", root.node("message-format").getString(),
                "a newly-added default key must be merged in");
        Assertions.assertTrue(Files.readString(file).contains("message-format"),
                "the merged config must be saved back");
    }

    @Test
    void aMissingBundledDefaultStillLoadsTheExistingFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discord.yml");
        Files.writeString(file, "bot-token: \"mine\"\n");

        ConfigurationNode root = ModuleConfigs.load(file, () -> (InputStream) null);

        Assertions.assertEquals("mine", root.node("bot-token").getString());
    }
}
