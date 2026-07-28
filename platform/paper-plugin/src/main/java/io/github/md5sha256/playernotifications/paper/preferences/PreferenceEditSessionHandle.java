package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import org.jetbrains.annotations.NotNull;

/**
 * Trivial wrapper letting {@link PreferenceRootDialog#show} re-invoke itself after a button mutates the
 * session in place (Mute everything / Reset all), without needing a second parameter list.
 */
record PreferenceEditSessionHandle(@NotNull PreferenceEditSession session) {
}
