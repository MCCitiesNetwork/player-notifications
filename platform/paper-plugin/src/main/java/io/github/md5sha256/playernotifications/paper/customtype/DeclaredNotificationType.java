package io.github.md5sha256.playernotifications.paper.customtype;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One notification type an operator declared in {@code notification-types.yml}.
 *
 * <p>Both configured values are raw <b>MiniMessage</b>, matching {@code messages.yml} and
 * {@code type-names.yml}, and both have already been proved to parse by
 * {@link CustomNotificationTypes#load} — nothing downstream needs to guard them.
 *
 * @param key         the registry {@code dataType}, lower-case and at most 64 characters
 * @param title       the title every notification of this type renders with; required
 * @param displayName the type's name in the preference screens, or {@code null} to fall through to
 *                    {@code type-names.yml} and then the title-cased key
 */
public record DeclaredNotificationType(@NotNull String key, @NotNull String title,
                                       @Nullable String displayName) {
}
