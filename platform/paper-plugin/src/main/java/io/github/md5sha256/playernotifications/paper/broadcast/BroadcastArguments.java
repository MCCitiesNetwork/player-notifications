package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses {@code /broadcast <content> --perm [perm] … [--bypass]} out of one greedy string. Brigadier
 * cannot express repeated flags, so the flags are pulled out of the raw argument here rather than in the
 * command tree, and that split is what makes the parsing rules unit-testable without a live server.
 *
 * <p>Everything before the first token equal to {@code --perm} or {@code --bypass} is the content; from
 * that token on, the remainder must be a sequence of {@code --perm <value>} pairs and bare
 * {@code --bypass} tokens, in any order. Anything else is rejected, naming the offending token — a
 * first-occurrence rule was chosen over scanning from the end because it produces a comprehensible error
 * for a malformed flag, whereas an end-scan would silently absorb a typo'd flag into the broadcast text,
 * which is worse for a command whose output every online player sees. The consequence, accepted: the
 * literal tokens {@code --perm} and {@code --bypass} can never appear in a broadcast's text.
 */
public record BroadcastArguments(@NotNull String content, @NotNull List<String> permissions,
                                 boolean bypass) {

    private static final String PERM_FLAG = "--perm";
    private static final String BYPASS_FLAG = "--bypass";

    /**
     * Parses {@code raw}. Content is taken as {@code raw.substring(0, tokenStart).trim()} rather than
     * re-joining split tokens, so interior spacing in the message survives untouched.
     */
    @NotNull
    public static Result parse(@NotNull String raw) {
        String[] tokens = raw.trim().isEmpty() ? new String[0] : raw.split("\\s+");
        int flagStart = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (PERM_FLAG.equals(tokens[i]) || BYPASS_FLAG.equals(tokens[i])) {
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

        Set<String> permissions = new LinkedHashSet<>();
        boolean bypass = false;
        for (int i = consumedTokenIndex; i < tokens.length; i++) {
            String token = tokens[i];
            if (BYPASS_FLAG.equals(token)) {
                bypass = true;
                continue;
            }
            if (PERM_FLAG.equals(token)) {
                if (i + 1 >= tokens.length || tokens[i + 1].startsWith("--")) {
                    return new Result.FlagMissingValue(PERM_FLAG);
                }
                permissions.add(tokens[i + 1]);
                i++;
                continue;
            }
            return new Result.UnrecognisedToken(token);
        }

        return new Result.Parsed(new BroadcastArguments(content, List.copyOf(permissions), bypass));
    }

    private static int tokenOffset(@NotNull String raw, @NotNull String[] tokens, int tokenIndex) {
        int offset = 0;
        for (int i = 0; i < tokenIndex; i++) {
            offset = raw.indexOf(tokens[i], offset) + tokens[i].length();
        }
        return raw.indexOf(tokens[tokenIndex], offset);
    }

    public sealed interface Result {

        record Parsed(@NotNull BroadcastArguments arguments) implements Result {
        }

        /**
          * The three rejection cases carry values rather than sentences, so that the wording lives in
          * {@code messages.yml} with every other player-facing string, and so this class's tests
          * assert on a case instead of on prose.
          */
        record BlankContent() implements Result {
        }

        /** {@code flag} was given with no value after it. */
        record FlagMissingValue(@NotNull String flag) implements Result {
        }

        /** A token that is neither part of the content nor a recognised flag — named, never absorbed. */
        record UnrecognisedToken(@NotNull String token) implements Result {
        }
    }
}
