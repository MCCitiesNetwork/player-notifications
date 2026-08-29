package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses {@code /broadcast <content> --perm [perm] … [--chain and|or] [--persistent] [--offline]
 * [--limit <n>] [--bypass]} out of one greedy string. Brigadier cannot express repeated flags, so the
 * flags are pulled out of the raw argument here rather than in the command tree, and that split is what
 * makes the parsing rules unit-testable without a live server.
 *
 * <p>Everything before the first flag token is the content; from that token on, the remainder must be a
 * sequence of value-taking flags ({@code --perm}, {@code --chain}, {@code --limit}) and bare flags
 * ({@code --bypass}, {@code --persistent}, {@code --offline}), in any order. Anything else is rejected,
 * naming the offending token — a first-occurrence rule was chosen over scanning from the end because it
 * produces a comprehensible error for a malformed flag, whereas an end-scan would silently absorb a
 * typo'd flag into the broadcast text, which is worse for a command whose output every online player
 * sees. The consequence, accepted: none of the literal flag tokens can appear in a broadcast's text.
 *
 * <p><b>Because an unrecognised token is rejected rather than absorbed, adding a flag is a pure
 * addition</b> — it can only turn text that was already a hard error into a flag, never change the
 * meaning of a command that parsed successfully before.
 *
 * @param content     the message, as raw MiniMessage
 * @param permissions the {@code --perm} nodes, de-duplicated, order preserved
 * @param bypass      whether to override the recipients' mute and silence
 * @param chain       how {@code permissions} combine; {@link Chain#OR} when {@code --chain} is absent
 * @param persistent  whether to store the broadcast as a real notification
 * @param offline     whether to widen the audience beyond currently-online players
 * @param limit       the maximum audience size, or {@code null} for unlimited
 */
public record BroadcastArguments(@NotNull String content, @NotNull List<String> permissions,
                                 boolean bypass, @NotNull Chain chain, boolean persistent,
                                 boolean offline, @Nullable Integer limit) {

    private static final String PERM_FLAG = "--perm";
    private static final String BYPASS_FLAG = "--bypass";
    private static final String CHAIN_FLAG = "--chain";
    private static final String PERSISTENT_FLAG = "--persistent";
    private static final String OFFLINE_FLAG = "--offline";
    private static final String LIMIT_FLAG = "--limit";

    private static final Set<String> FLAG_TOKENS = Set.of(
            PERM_FLAG, BYPASS_FLAG, CHAIN_FLAG, PERSISTENT_FLAG, OFFLINE_FLAG, LIMIT_FLAG);

    /** How multiple {@code --perm} nodes combine when selecting recipients. */
    public enum Chain {
        AND,
        OR
    }

    /**
     * Parses {@code raw}. Content is taken as {@code raw.substring(0, tokenStart).trim()} rather than
     * re-joining split tokens, so interior spacing in the message survives untouched.
     */
    @NotNull
    public static Result parse(@NotNull String raw) {
        String[] tokens = raw.trim().isEmpty() ? new String[0] : raw.split("\\s+");
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

        Set<String> permissions = new LinkedHashSet<>();
        boolean bypass = false;
        boolean persistent = false;
        boolean offline = false;
        Chain chain = Chain.OR;
        Integer limit = null;

        for (int i = consumedTokenIndex; i < tokens.length; i++) {
            String token = tokens[i];
            switch (token) {
                case BYPASS_FLAG -> bypass = true;
                case PERSISTENT_FLAG -> persistent = true;
                case OFFLINE_FLAG -> offline = true;
                case PERM_FLAG, CHAIN_FLAG, LIMIT_FLAG -> {
                    if (!hasValueAt(tokens, i + 1)) {
                        return new Result.FlagMissingValue(token);
                    }
                    String value = tokens[++i];
                    switch (token) {
                        case PERM_FLAG -> permissions.add(value);
                        case CHAIN_FLAG -> {
                            Chain parsedChain = parseChain(value);
                            if (parsedChain == null) {
                                return new Result.UnknownChainValue(value);
                            }
                            // Last occurrence wins, consistent with --bypass's idempotence.
                            chain = parsedChain;
                        }
                        default -> {
                            Integer parsedLimit = parseLimit(value);
                            if (parsedLimit == null) {
                                return new Result.InvalidLimitValue(value);
                            }
                            limit = parsedLimit;
                        }
                    }
                }
                default -> {
                    return new Result.UnrecognisedToken(token);
                }
            }
        }

        // Checked after the loop rather than inline, because both depend on flags that may appear in
        // any order relative to --offline.
        if (offline && !persistent) {
            return new Result.OfflineRequiresPersistent();
        }
        if (offline && permissions.isEmpty()) {
            return new Result.OfflineRequiresPermission();
        }

        return new Result.Parsed(new BroadcastArguments(content, List.copyOf(permissions), bypass,
                chain, persistent, offline, limit));
    }

    private static boolean hasValueAt(@NotNull String[] tokens, int index) {
        return index < tokens.length && !tokens[index].startsWith("--");
    }

    @Nullable
    private static Chain parseChain(@NotNull String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "and" -> Chain.AND;
            case "or" -> Chain.OR;
            default -> null;
        };
    }

    /**
     * {@code null} for anything that is not an integer of at least 1. Zero is rejected rather than
     * treated as "unlimited": it reads naturally as "send to nobody", and overloading it would make the
     * most dangerous setting look like the safest. Absent means unlimited; there is no magic value.
     */
    @Nullable
    private static Integer parseLimit(@NotNull String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
        return parsed < 1 ? null : parsed;
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
          * The rejection cases carry values rather than sentences, so that the wording lives in
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

        /** {@code --chain} was given something other than {@code and} or {@code or}. */
        record UnknownChainValue(@NotNull String value) implements Result {
        }

        /** {@code --limit} was given a non-integer, zero, or a negative value. */
        record InvalidLimitValue(@NotNull String value) implements Result {
        }

        /**
         * {@code --offline} without {@code --persistent}. An offline player has no chat, and
         * {@code ChatSink} reports {@code DELIVERED} for an absent player, so a transient offline
         * broadcast would claim a success nobody saw.
         */
        record OfflineRequiresPersistent() implements Result {
        }

        /**
         * {@code --offline} with no {@code --perm}. The permission filter is the only thing bounding
         * the shape of an offline audience, so its absence would address every player the permission
         * backend has ever heard of.
         */
        record OfflineRequiresPermission() implements Result {
        }
    }
}
