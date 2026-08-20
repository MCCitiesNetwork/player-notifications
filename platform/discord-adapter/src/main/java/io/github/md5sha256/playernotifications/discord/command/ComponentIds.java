package io.github.md5sha256.playernotifications.discord.command;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The codec for the {@code customId} carried by every button and select this module posts.
 *
 * <p>Discord hands a component's id back verbatim on the interaction, which is what lets this surface
 * hold no per-user state at all: the page being viewed and the notification being acted on travel in
 * the id rather than in a cursor the module would have to expire and clean up. The in-game
 * {@code InboxRouter} needs such a cursor and the stale-entry guarding that comes with it; nothing
 * here does.
 *
 * <p>Format is {@code pn|<surface>|<action>|<arg>…}. An id from another plugin, or one this class did
 * not write, parses to empty rather than throwing — a listener sees other plugins' interactions too.
 */
public final class ComponentIds {

    /** The inbox surface for {@code /notifications}. */
    public static final String SURFACE_INBOX = "inbox";

    /** The inbox surface for {@code /mail}, distinct so a mail listing cannot be answered unfiltered. */
    public static final String SURFACE_INBOX_MAIL = "inbox-mail";

    /** The preference surface. */
    public static final String SURFACE_PREFS = "prefs";

    /** Discord rejects a message carrying a custom id longer than this. */
    public static final int MAX_LENGTH = 100;

    private static final String PREFIX = "pn";
    private static final String DELIMITER = "|";

    private ComponentIds() {
    }

    /**
     * Encodes one component id.
     *
     * @throws IllegalArgumentException if an argument contains the delimiter, or the result exceeds
     *                                  {@link #MAX_LENGTH} — both would otherwise surface as a failed
     *                                  interaction rather than as a visibly missing button
     */
    public static @NotNull String encode(@NotNull String surface, @NotNull String action,
                                         @NotNull String... args) {
        StringBuilder builder = new StringBuilder(PREFIX).append(DELIMITER)
                .append(surface).append(DELIMITER).append(action);
        for (String arg : args) {
            if (arg.contains(DELIMITER)) {
                throw new IllegalArgumentException("Component id argument contains '" + DELIMITER + "': " + arg);
            }
            builder.append(DELIMITER).append(arg);
        }
        String id = builder.toString();
        if (id.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Component id is " + id.length() + " characters, over Discord's " + MAX_LENGTH + ": " + id);
        }
        return id;
    }

    /** The parsed id, or empty when it is not one of ours or carries no action. */
    public static @NotNull Optional<Parsed> parse(@NotNull String customId) {
        String[] parts = customId.split("\\" + DELIMITER, -1);
        if (parts.length < 3 || !PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(parts[1], parts[2], List.of(parts).subList(3, parts.length)));
    }

    /** One decoded component id. */
    public record Parsed(@NotNull String surface, @NotNull String action, @NotNull List<String> args) {

        /** The argument at {@code index}, or empty if there is none. */
        public @NotNull Optional<String> arg(int index) {
            return index >= 0 && index < this.args.size()
                    ? Optional.of(this.args.get(index))
                    : Optional.empty();
        }

        /** The argument at {@code index} as an int, or empty if absent or not a number. */
        public @NotNull OptionalInt intArg(int index) {
            Optional<String> value = arg(index);
            if (value.isEmpty()) {
                return OptionalInt.empty();
            }
            try {
                return OptionalInt.of(Integer.parseInt(value.get()));
            } catch (NumberFormatException exception) {
                return OptionalInt.empty();
            }
        }
    }
}
