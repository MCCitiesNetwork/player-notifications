package io.github.md5sha256.playernotifications.api.link;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Links a player's Minecraft account to an account on some external service, and answers the three
 * player-facing questions about that link: start one, describe the current one, remove it.
 *
 * <p>Registered in the {@link AccountLinkRegistry} under {@link #providerKey()} and surfaced by the host
 * as {@code /notifications link <providerKey>}. Adding a service means writing one of these and
 * registering it — the command tree does not change.
 *
 * <p>Every method <em>returns</em> the player-facing reply rather than sending it: the host owns the
 * sender and the threading. Implementations may block (this one talks to a database), so the host
 * dispatches them off the main thread. No method should throw — an implementation that does is contained
 * and logged by the host, but the player then gets a generic reply instead of a useful one.
 */
public interface AccountLinkProvider {

    /**
     * The key this provider is registered and addressed under, e.g. {@code "discord"}. Matched
     * case-insensitively, and normalised to lower case by the registry.
     */
    @NotNull String providerKey();

    /**
     * A human-readable name for the service, used to label it in player-facing messages. Defaults to a
     * title-cased rendering of {@link #providerKey()}, mirroring
     * {@link io.github.md5sha256.playernotifications.api.render.NotificationSink#displayName()}.
     */
    default @NotNull Component displayName() {
        return Component.text(titleCase(providerKey()));
    }

    /** Starts a link: the reply to a bare {@code /notifications link <provider>}. */
    @NotNull Component begin(@NotNull UUID playerUuid);

    /** Describes the player's current link, if any. */
    @NotNull Component status(@NotNull UUID playerUuid);

    /** Removes the player's link, if any. */
    @NotNull Component unlink(@NotNull UUID playerUuid);

    /**
     * Title-cases a provider key: {@code '-'} and {@code '_'} separate words, each word is capitalized,
     * and words are rejoined with spaces. A deliberate copy of the identical helper on
     * {@code NotificationSink} — the two live in different packages and neither should become public API
     * for the sake of fifteen lines.
     */
    private static @NotNull String titleCase(@NotNull String providerKey) {
        StringBuilder builder = new StringBuilder(providerKey.length());
        boolean startOfWord = true;
        for (int i = 0; i < providerKey.length(); i++) {
            char c = providerKey.charAt(i);
            if (c == '-' || c == '_') {
                builder.append(' ');
                startOfWord = true;
                continue;
            }
            builder.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = false;
        }
        String titled = builder.toString();
        return titled.isEmpty() ? providerKey : titled;
    }

}
