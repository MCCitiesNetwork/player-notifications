package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.link.AccountLinkProvider;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Surfaces this module's account linking to the host as {@code /notifications link discord}.
 *
 * <p>A thin delegate over {@link DiscordLinkFlow} for the same reason the module's former root command
 * was: every decision and every message lives in the flow, which is unit tested, and this class has
 * nothing left to get wrong.
 *
 * <p>Registered only when {@code link-providers} lists {@code embedded} — a DiscordSRV-only server has
 * nothing for a code to be redeemed into, so it offers no linking rather than a command that cannot work.
 */
public final class DiscordAccountLinkProvider implements AccountLinkProvider {

    private final DiscordLinkFlow flow;

    public DiscordAccountLinkProvider(@NotNull DiscordLinkFlow flow) {
        this.flow = flow;
    }

    @Override
    public @NotNull String providerKey() {
        return DiscordMedia.LINK_PROVIDER_KEY;
    }

    @Override
    public @NotNull Component begin(@NotNull UUID playerUuid) {
        return this.flow.begin(playerUuid);
    }

    @Override
    public @NotNull Component status(@NotNull UUID playerUuid) {
        return this.flow.status(playerUuid);
    }

    @Override
    public @NotNull Component unlink(@NotNull UUID playerUuid) {
        return this.flow.unlink(playerUuid);
    }
}
