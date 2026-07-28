package io.github.md5sha256.playernotifications.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Converts an Adventure {@link Component} into Discord-flavoured markdown.
 *
 * <p>Decorations map onto Discord's markers: bold {@code **}, italic {@code *}, underlined
 * {@code __}, strikethrough {@code ~~}, and obfuscated onto a spoiler {@code ||} (the closest
 * analogue Discord offers to hidden text). Colours are dropped — Discord message text cannot be
 * coloured; only an embed's accent bar can, which is {@link DiscordMessageFactory}'s concern.
 *
 * <p>Literal text has Discord's markdown characters escaped so a payload containing an asterisk does
 * not accidentally emphasise the rest of the message.
 */
public final class DiscordMarkdownSerializer {

    /**
     * The decorations that produce a marker, in the order markers are opened. A fixed order keeps
     * output deterministic and nesting well-formed.
     */
    private static final List<TextDecoration> ORDERED_DECORATIONS = List.of(
            TextDecoration.BOLD,
            TextDecoration.ITALIC,
            TextDecoration.UNDERLINED,
            TextDecoration.STRIKETHROUGH,
            TextDecoration.OBFUSCATED);

    /** Characters Discord treats as markdown syntax, escaped with a backslash in literal text. */
    private static final String ESCAPED_CHARACTERS = "\\*_~|`>";

    private DiscordMarkdownSerializer() {
    }

    /**
     * Serializes the given component to Discord markdown.
     */
    public static @NotNull String serialize(@NotNull Component component) {
        MarkdownListener listener = new MarkdownListener();
        ComponentFlattener.basic().flatten(component, listener);
        return listener.finish();
    }

    private static @NotNull String marker(@NotNull TextDecoration decoration) {
        return switch (decoration) {
            case BOLD -> "**";
            case ITALIC -> "*";
            case UNDERLINED -> "__";
            case STRIKETHROUGH -> "~~";
            case OBFUSCATED -> "||";
        };
    }

    private static final class MarkdownListener implements FlattenerListener {

        private final StringBuilder output = new StringBuilder();
        private final Deque<Style> styles = new ArrayDeque<>();
        /** Markers currently open, innermost last. */
        private final List<TextDecoration> open = new ArrayList<>();

        @Override
        public void pushStyle(@NotNull Style style) {
            this.styles.addLast(style);
        }

        @Override
        public void popStyle(@NotNull Style style) {
            this.styles.pollLast();
        }

        @Override
        public void component(@NotNull String text) {
            if (text.isEmpty()) {
                return;
            }
            applyDecorations(effectiveDecorations());
            escapeInto(text, this.output);
        }

        private @NotNull Set<TextDecoration> effectiveDecorations() {
            EnumSet<TextDecoration> effective = EnumSet.noneOf(TextDecoration.class);
            // Outermost first: an inner style's explicit TRUE/FALSE overrides whatever it inherited.
            for (Style style : this.styles) {
                for (TextDecoration decoration : ORDERED_DECORATIONS) {
                    switch (style.decoration(decoration)) {
                        case TRUE -> effective.add(decoration);
                        case FALSE -> effective.remove(decoration);
                        case NOT_SET -> {
                            // Inherited from the enclosing style; leave as-is.
                        }
                    }
                }
            }
            return effective;
        }

        private void applyDecorations(@NotNull Set<TextDecoration> wanted) {
            // Close from the innermost marker outwards until every still-open marker is wanted.
            // Closing an outer marker without first closing the inner ones would interleave.
            while (!this.open.isEmpty() && !wanted.containsAll(this.open)) {
                closeInnermost();
            }
            for (TextDecoration decoration : ORDERED_DECORATIONS) {
                if (wanted.contains(decoration) && !this.open.contains(decoration)) {
                    this.output.append(marker(decoration));
                    this.open.add(decoration);
                }
            }
        }

        private void closeInnermost() {
            TextDecoration decoration = this.open.remove(this.open.size() - 1);
            this.output.append(marker(decoration));
        }

        private @NotNull String finish() {
            while (!this.open.isEmpty()) {
                closeInnermost();
            }
            return this.output.toString();
        }

        private static void escapeInto(@NotNull String text, @NotNull StringBuilder builder) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (ESCAPED_CHARACTERS.indexOf(c) >= 0) {
                    builder.append('\\');
                }
                builder.append(c);
            }
        }
    }
}
