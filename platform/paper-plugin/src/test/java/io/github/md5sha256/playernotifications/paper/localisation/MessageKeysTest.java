package io.github.md5sha256.playernotifications.paper.localisation;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one failure mode this whole mechanism has: {@code messageFor} renders a key it does not
 * know as the key's own name, so a mismatch between {@link MessageKeys} and {@code messages.yml}
 * reaches a player as the literal text {@code mail.sent} instead of throwing anywhere.
 *
 * <p>Both directions are asserted. Only checking declared-to-shipped would let a key be added to the
 * file and never wired up; only checking shipped-to-declared would let a constant point at nothing.
 */
class MessageKeysTest {

    @Test
    void everyDeclaredKeyIsPresentInTheShippedFile() throws IllegalAccessException {
        MessageContainer messages = TestMessages.shipped();

        for (String key : declaredKeys()) {
            assertNotEquals(key, messages.miniMessageFormattedFor(key),
                    "MessageKeys declares '" + key + "' but messages.yml has no such key, so it "
                            + "would render as its own name in game");
        }
    }

    @Test
    void everyShippedKeyHasADeclaredConstant() throws IllegalAccessException {
        Set<String> declared = new HashSet<>(declaredKeys());

        for (String key : TestMessages.shippedKeys()) {
            assertTrue(declared.contains(key),
                    "messages.yml defines '" + key + "' but MessageKeys declares no constant for "
                            + "it, so nothing can read it");
        }
    }

    @Test
    void declaredKeysAreUnique() throws IllegalAccessException {
        List<String> keys = declaredKeys();

        assertEquals(keys.size(), new HashSet<>(keys).size(),
                "two MessageKeys constants share a key: " + keys);
    }

    private static List<String> declaredKeys() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Field field : MessageKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                keys.add((String) field.get(null));
            }
        }
        assertTrue(keys.size() > 20, "reflection found almost no keys — the walk is broken");
        return keys;
    }
}
