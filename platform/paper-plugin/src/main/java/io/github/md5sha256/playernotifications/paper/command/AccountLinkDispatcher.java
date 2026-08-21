package io.github.md5sha256.playernotifications.paper.command;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.api.link.AccountLinkProvider;
import io.github.md5sha256.playernotifications.api.link.AccountLinkRegistry;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything {@code /notifications link} does apart from the Brigadier node itself: resolving a provider
 * key, invoking the right action, and turning "no such provider" or "that provider threw" into a reply.
 *
 * <p>Split out from {@link NotificationsCommand} precisely so it can be unit tested — the command node
 * needs a live server, this does not. It touches no Bukkit type and sends nothing; the caller owns the
 * sender and the threading, and must call from off the main thread because providers block.
 */
public final class AccountLinkDispatcher {

    /** The three things a player can ask of a provider, mirroring {@link AccountLinkProvider}. */
    public enum Action {
        BEGIN,
        STATUS,
        UNLINK
    }

    private final MessageContainer messages;
    private final AccountLinkRegistry registry;
    private final Logger logger;

    public AccountLinkDispatcher(@NotNull MessageContainer messages,
                                 @NotNull AccountLinkRegistry registry,
                                 @NotNull Logger logger) {
        this.messages = messages;
        this.registry = registry;
        this.logger = logger;
    }

    /**
     * The reply to a bare {@code /notifications link}: what can be linked, and how.
     *
     * <p>Read from the registry on every call rather than cached, because the Brigadier tree is built
     * once and modules may register after that.
     */
    public @NotNull Component listProviders() {
        List<String> keys = new ArrayList<>(this.registry.registeredProviders());
        keys.sort(String::compareTo);
        if (keys.isEmpty()) {
            return this.messages.messageFor(MessageKeys.LINK_NONE_AVAILABLE);
        }

        TextComponent.Builder builder = Component.text()
                .append(this.messages.messageFor(MessageKeys.LINK_HEADER));
        for (String key : keys) {
            Component name = this.registry.getProvider(key)
                    .map(AccountLinkProvider::displayName)
                    .orElseGet(() -> Component.text(key));
            builder.append(Component.newline())
                    .append(this.messages.messageFor(MessageKeys.LINK_ENTRY,
                            MessageContainer.markup("name", name),
                            MessageContainer.value("key", key)));
        }
        return builder.build();
    }

    /**
     * Runs {@code action} against the provider registered under {@code providerKey} and returns its
     * reply, or an explanatory message when the provider is absent or misbehaves.
     */
    public @NotNull Component dispatch(@NotNull String providerKey,
                                       @NotNull UUID playerUuid,
                                       @NotNull Action action) {
        Optional<AccountLinkProvider> provider = this.registry.getProvider(providerKey);
        if (provider.isEmpty()) {
            // Also the message a player sees after the owning module is stopped: the command node stays
            // in the tree, so absent-provider and never-installed are deliberately one code path.
            return this.messages.messageFor(MessageKeys.LINK_UNAVAILABLE,
                    MessageContainer.markup("name",
                            AccountLinkProvider.defaultDisplayName(providerKey)));
        }

        Function<UUID, Component> method = switch (action) {
            case BEGIN -> provider.get()::begin;
            case STATUS -> provider.get()::status;
            case UNLINK -> provider.get()::unlink;
        };
        try {
            return method.apply(playerUuid);
        } catch (RuntimeException exception) {
            // A module's bug must not reach the player as a Brigadier stack trace.
            this.logger.log(Level.WARNING, "Account link provider '" + providerKey
                    + "' failed handling " + action + " for " + playerUuid, exception);
            return this.messages.messageFor(MessageKeys.COMMON_ERROR);
        }
    }

    /** Tab-completion candidates, read live for the same reason {@link #listProviders()} is. */
    public @NotNull Collection<String> suggestions() {
        return this.registry.registeredProviders();
    }

}
