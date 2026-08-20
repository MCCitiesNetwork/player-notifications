package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Covers the player-level mute flag: {@link DatabaseNotificationPreferences#isMuted}, {@code mute} and
 * {@code unmute}, backed by the {@code PlayerNotificationMute} table.
 */
class PlayerMuteTest extends AbstractDatabaseTest {

    @Test
    @DisplayName("isMuted is false by default for a fresh player")
    void isMutedIsFalseByDefault() {
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("chat"));

        Assertions.assertFalse(preferences.isMuted(UUID.randomUUID()));
    }

    @Test
    @DisplayName("mute then isMuted reports true")
    void muteThenIsMuted() {
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("chat"));
        UUID player = UUID.randomUUID();

        preferences.mute(player);

        Assertions.assertTrue(preferences.isMuted(player));
    }

    @Test
    @DisplayName("muting twice does not throw and leaves the player muted")
    void muteIsIdempotent() {
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("chat"));
        UUID player = UUID.randomUUID();

        Assertions.assertDoesNotThrow(() -> {
            preferences.mute(player);
            preferences.mute(player);
        });
        Assertions.assertTrue(preferences.isMuted(player));
    }

    @Test
    @DisplayName("unmute clears a previously set mute")
    void unmuteClearsIt() {
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("chat"));
        UUID player = UUID.randomUUID();

        preferences.mute(player);
        preferences.unmute(player);

        Assertions.assertFalse(preferences.isMuted(player));
    }

    @Test
    @DisplayName("unmute on a never-muted player is a no-op")
    void unmuteOnUnmutedPlayerIsANoOp() {
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("chat"));
        UUID player = UUID.randomUUID();

        Assertions.assertDoesNotThrow(() -> preferences.unmute(player));
        Assertions.assertFalse(preferences.isMuted(player));
    }

    @Test
    @DisplayName("muting a player leaves their per-dataType preference rows untouched")
    void mutePreservesPreferenceRows() {
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("chat"));
        UUID player = UUID.randomUUID();
        preferences.applyChanges(player, Map.of("test", Set.of("chat")), Set.of());

        preferences.mute(player);

        Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(player, "test"));
        Assertions.assertTrue(preferences.isMuted(player));
    }
}
