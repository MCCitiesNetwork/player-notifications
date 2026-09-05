package io.github.md5sha256.playernotifications.paper.send;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Parses {@code /notifications send <player> <type> <content> [--transient] [--bypass]}'s content
 * argument out of one greedy string.
 *
 * <p>The flag is pulled out of the raw argument rather than expressed as a Brigadier node because
 * Brigadier cannot put an optional literal <em>after</em> a greedy argument. The rules are
 * {@code BroadcastArguments}' rules with two of its flags, deliberately identical so the two commands do not
 * disagree about what a flag is: everything before the <b>first</b> recognised flag token is the
 * content, taken as a substring of the raw input so interior spacing survives untouched, and every
 * token from there on must be a recognised flag or the whole command is rejected naming it.
 *
 * <p>Two consequences are inherited along with the rule. Neither literal flag token can
 * appear in a notification's text; and an unknown token such as {@code --wat} is only rejected when it
 * <em>follows</em> a recognised flag — before one, it is simply part of the content, since nothing has
 * yet told the parser the message has ended.
 *
 * @param content       the message, as raw MiniMessage
 * @param transientSend whether to fan the notification out without storing it
 * @param bypass        whether to deliver regardless of the recipient's mute and silence, as
 *                      {@code /broadcast --bypass} does — the two are one flag, since an
 *                      announcement that is not a subscription is not one for either reason
 */
public record SendArguments(@NotNull String content, boolean transientSend, boolean bypass) {

    private static final String TRANSIENT_FLAG = "--transient";

    private static final String BYPASS_FLAG = "--bypass";

    private static final Set<String> FLAG_TOKENS = Set.of(TRANSIENT_FLAG, BYPASS_FLAG);

    /**
     * Parses {@code raw}. Content is taken as {@code raw.substring(0, tokenStart).trim()} rather than
     * re-joining split tokens, so interior spacing in the message survives untouched.
     */
    @NotNull
    public static Result parse(@NotNull String raw) {
        String[] tokens = raw.trim().isEmpty() ? new String[0] : raw.trim().split("\\s+");
        int flagStart = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (FLAG_TOKENS.contains(tokens[i])) {
                flagStart = i;
                break;
            }
        }

        String content;
        int consumedTokenIndex;
        if (flagStart < 0) {
            content = raw.trim();
            consumedTokenIndex = tokens.length;
        } else {
            int tokenStart = tokenOffset(raw, tokens, flagStart);
            content = raw.substring(0, tokenStart).trim();
            consumedTokenIndex = flagStart;
        }

        if (content.isBlank()) {
            return new Result.BlankContent();
        }

        boolean transientSend = false;
        boolean bypass = false;
        for (int i = consumedTokenIndex; i < tokens.length; i++) {
            String token = tokens[i];
            // Repeating either is idempotent, consistent with /broadcast's --bypass.
            if (TRANSIENT_FLAG.equals(token)) {
                transientSend = true;
            } else if (BYPASS_FLAG.equals(token)) {
                bypass = true;
            } else {
                return new Result.UnrecognisedToken(token);
            }
        }

        return new Result.Parsed(new SendArguments(content, transientSend, bypass));
    }

    private static int tokenOffset(@NotNull String raw, @NotNull String[] tokens, int tokenIndex) {
        int offset = 0;
        for (int i = 0; i < tokenIndex; i++) {
            offset = raw.indexOf(tokens[i], offset) + tokens[i].length();
        }
        return raw.indexOf(tokens[tokenIndex], offset);
    }

    /**
     * The rejection cases carry values rather than sentences, so the wording lives in
     * {@code messages.yml} with every other player-facing string and this class's tests assert on a
     * case rather than on prose.
     */
    public sealed interface Result {

        record Parsed(@NotNull SendArguments arguments) implements Result {
        }

        /** The content was blank, or the command was nothing but flags. */
        record BlankContent() implements Result {
        }

        /** A token that is neither part of the content nor a recognised flag — named, never absorbed. */
        record UnrecognisedToken(@NotNull String token) implements Result {
        }
    }
}
