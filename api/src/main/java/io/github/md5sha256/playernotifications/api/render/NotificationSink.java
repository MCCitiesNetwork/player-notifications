package io.github.md5sha256.playernotifications.api.render;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Delivers a {@link RenderableNotification} over a single medium (chat, a dialog, Essentials mail,
 * Discord, ...). One sink is written per medium, independent of the payload types that flow through it
 * — this is the other half of the N + M split described by {@link NotificationRenderer}.
 */
public interface NotificationSink {

    /**
     * The medium key this sink is registered under in the {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry}
     * (e.g. {@code "chat"}, {@code "dialog"}, {@code "essentials-mail"}, {@code "discord"}).
     */
    @NotNull String mediumKey();

    /**
     * Attempts to deliver the given notification to the given target over this sink's medium.
     */
    @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target);

    /**
     * A human-readable name for this medium, used to label it in player-facing UI such as the
     * notification preferences dialog. Naming stays with the medium owner, consistent with the N + M
     * ownership model: whoever writes the sink names it.
     *
     * <p>Defaults to a title-cased rendering of {@link #mediumKey()} ({@code "essentials-mail"} becomes
     * {@code "Essentials Mail"}), so existing and third-party sinks need no change.
     */
    default @NotNull Component displayName() {
        return Component.text(titleCase(mediumKey()));
    }

    /**
     * An optional one-line explanation of what this medium does, shown alongside {@link #displayName()}
     * as a tooltip. Defaults to empty, meaning "no tooltip".
     */
    default @NotNull Component description() {
        return Component.empty();
    }

    /**
     * Title-cases a medium key: {@code '-'} and {@code '_'} separate words, each word is capitalized,
     * and words are rejoined with spaces.
     */
    private static @NotNull String titleCase(@NotNull String mediumKey) {
        StringBuilder builder = new StringBuilder(mediumKey.length());
        boolean startOfWord = true;
        for (int i = 0; i < mediumKey.length(); i++) {
            char c = mediumKey.charAt(i);
            if (c == '-' || c == '_') {
                builder.append(' ');
                startOfWord = true;
                continue;
            }
            builder.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = false;
        }
        String titled = builder.toString();
        return titled.isEmpty() ? mediumKey : titled;
    }

}
