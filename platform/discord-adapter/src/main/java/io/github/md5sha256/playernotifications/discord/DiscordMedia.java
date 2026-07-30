package io.github.md5sha256.playernotifications.discord;

/**
 * The medium keys this adapter owns in the
 * {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry}.
 */
public final class DiscordMedia {

    /** Direct message from the server's Discord bot. Registered by {@link DiscordDmSink}. */
    public static final String DM = "discord-dm";

    /**
     * Reserved for a future sink that posts into a channel and pings the player instead of DMing
     * them. Nothing registers a sink under this key today, so it never appears in the preference
     * dialogs — those enumerate {@code sinkRegistry().registeredMedia()}. The name is claimed here so
     * the DM sink is not squatting on a generic {@code "discord"} key, and a channel sink can be
     * added later without migrating any stored preference row.
     */
    public static final String CHANNEL_PING = "discord-channel-ping";

    /**
     * The key this adapter registers its
     * {@link io.github.md5sha256.playernotifications.api.link.AccountLinkProvider} under, addressed as
     * {@code /notifications link discord}. Deliberately the bare service name, not a medium key: linking
     * is per-service, and one link serves every Discord medium.
     */
    public static final String LINK_PROVIDER_KEY = "discord";

    private DiscordMedia() {
    }
}
