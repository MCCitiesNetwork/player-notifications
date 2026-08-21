package io.github.md5sha256.playernotifications.paper.localisation;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands tests a {@link MessageContainer} loaded from the <strong>shipped</strong>
 * {@code messages.yml}.
 *
 * <p>Deliberately not a hand-built fixture: a fixture drifts from what actually ships, and the
 * container renders a missing key as the key itself, so the drift would surface in game rather than
 * in a test. Loading the real file means every wording assertion in the suite is also an assertion
 * that the default is present and spelled the way the code asks for it.
 */
public final class TestMessages {

    private TestMessages() {
    }

    /** A fresh container populated from {@code messages.yml} on the classpath. */
    public static MessageContainer shipped() {
        MessageContainer container = new MessageContainer();
        container.load(shippedNode());
        return container;
    }

    /** Every key in {@code messages.yml}, flattened to the dotted form the container stores. */
    public static List<String> shippedKeys() {
        List<String> keys = new ArrayList<>();
        flatten("", shippedNode(), keys);
        return keys;
    }

    private static ConfigurationNode shippedNode() {
        InputStream source = TestMessages.class.getResourceAsStream("/messages.yml");
        if (source == null) {
            throw new IllegalStateException("messages.yml is not on the test classpath");
        }
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            return YamlConfigurationLoader.builder().source(() -> reader).build().load();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read messages.yml", exception);
        }
    }

    private static void flatten(String prefix, ConfigurationNode node, List<String> into) {
        if (node.isMap()) {
            node.childrenMap().forEach((key, child) ->
                    flatten(prefix.isEmpty() ? key.toString() : prefix + "." + key, child, into));
        } else if (!prefix.isEmpty()) {
            into.add(prefix);
        }
    }
}
