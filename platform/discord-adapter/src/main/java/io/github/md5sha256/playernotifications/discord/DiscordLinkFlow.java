package io.github.md5sha256.playernotifications.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every decision and every player-facing message in the account-link flow.
 *
 * <p>{@link DiscordLinkCommand} and {@link LinkSlashCommandListener} are deliberately thin adapters over
 * this class: the command side cannot be unit tested without a server and the Discord side cannot without
 * a bot, so keeping all the logic here is what makes the flow testable at all.
 *
 * <p>Every method may be called from a command thread or a JDA event thread and blocks on JDBC, so callers
 * must be off the main thread. No method throws: a database failure becomes an error message or
 * {@link RedeemResult#FAILED}, because a command and an interaction both need a reply.
 */
public final class DiscordLinkFlow {

    /** The outcome of a Discord-side redemption, mapped to a reply by the listener. */
    public enum RedeemResult {
        /** The link was written. */
        LINKED,
        /**
         * The code is unknown <em>or</em> expired. Not distinguished: {@link LinkCodeService#redeem}
         * removes an expired entry, so nothing remains to tell the two apart. The reply names both.
         */
        UNKNOWN_CODE,
        /** The redeeming Discord account is already linked to that same player; nothing changed. */
        ALREADY_LINKED_TO_THIS_ACCOUNT,
        /** The store failed. The code has already been consumed, so the player must request a new one. */
        FAILED
    }

    private final DiscordAccountLinkStore store;
    private final LinkCodeService codes;
    private final Logger logger;

    public DiscordLinkFlow(@NotNull DiscordAccountLinkStore store,
                           @NotNull LinkCodeService codes,
                           @NotNull Logger logger) {
        this.store = store;
        this.codes = codes;
        this.logger = logger;
    }

    /** The reply to a bare {@code /discordlink}: issue a code and explain how to redeem it. */
    public @NotNull Component begin(@NotNull UUID playerUuid) {
        Optional<Long> existing;
        try {
            existing = this.store.discordIdFor(playerUuid);
        } catch (RuntimeException exception) {
            return failure("Could not check your Discord link", exception);
        }

        if (existing.isPresent()) {
            return Component.text()
                    .append(Component.text("Your account is already linked to Discord. Run ",
                            NamedTextColor.YELLOW))
                    .append(Component.text("/discordlink unlink", NamedTextColor.AQUA))
                    .append(Component.text(" first if you want to link a different account.",
                            NamedTextColor.YELLOW))
                    .build();
        }

        String code = this.codes.issue(playerUuid);
        long minutes = Math.max(1L, this.codes.ttl().toMinutes());
        return Component.text()
                .append(Component.text("Your Discord link code is ", NamedTextColor.GREEN))
                .append(Component.text(code, NamedTextColor.GOLD))
                .append(Component.text(". Send ", NamedTextColor.GREEN))
                .append(Component.text("/link " + code, NamedTextColor.AQUA))
                .append(Component.text(" to the bot in Discord within " + minutes + " minutes.",
                        NamedTextColor.GREEN))
                .build();
    }

    /** The reply to {@code /discordlink status}. */
    public @NotNull Component status(@NotNull UUID playerUuid) {
        Optional<Long> linked;
        try {
            linked = this.store.discordIdFor(playerUuid);
        } catch (RuntimeException exception) {
            return failure("Could not read your Discord link", exception);
        }

        if (linked.isPresent()) {
            return Component.text()
                    .append(Component.text("Linked to Discord user ", NamedTextColor.GREEN))
                    .append(Component.text(Long.toString(linked.get()), NamedTextColor.GOLD))
                    .append(Component.text(".", NamedTextColor.GREEN))
                    .build();
        }

        Component base = Component.text("Your account is not linked to Discord.", NamedTextColor.YELLOW);
        if (this.codes.hasOutstandingCode(playerUuid)) {
            return base.append(Component.text(
                    " You have a link code outstanding — redeem it in Discord, or run /discordlink"
                            + " again for a new one.", NamedTextColor.YELLOW));
        }
        return base.append(Component.text(" Run /discordlink to start.", NamedTextColor.YELLOW));
    }

    /** The reply to {@code /discordlink unlink}. */
    public @NotNull Component unlink(@NotNull UUID playerUuid) {
        // Cancel first: even if the delete fails, an outstanding code should not survive an unlink attempt.
        this.codes.cancel(playerUuid);
        boolean removed;
        try {
            removed = this.store.unlink(playerUuid);
        } catch (RuntimeException exception) {
            return failure("Could not remove your Discord link", exception);
        }

        if (removed) {
            return Component.text("Your Discord account has been unlinked.", NamedTextColor.GREEN);
        }
        return Component.text("Your account is not linked to Discord.", NamedTextColor.YELLOW);
    }

    /**
     * Redeems a code on behalf of a Discord user. Called from the JDA listener.
     *
     * @param code      the code as typed by the Discord user
     * @param discordId the redeeming Discord user's id
     */
    public @NotNull RedeemResult redeem(@NotNull String code, long discordId) {
        Optional<UUID> player = this.codes.redeem(code);
        if (player.isEmpty()) {
            return RedeemResult.UNKNOWN_CODE;
        }
        UUID playerUuid = player.get();

        try {
            if (this.store.discordIdFor(playerUuid).filter(existing -> existing == discordId).isPresent()) {
                return RedeemResult.ALREADY_LINKED_TO_THIS_ACCOUNT;
            }
            this.store.link(playerUuid, discordId);
            return RedeemResult.LINKED;
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING,
                    "Failed to link " + playerUuid + " to Discord user " + discordId, exception);
            return RedeemResult.FAILED;
        }
    }

    private @NotNull Component failure(@NotNull String what, @NotNull RuntimeException exception) {
        this.logger.log(Level.WARNING, what + " (database failure)", exception);
        return Component.text(what + " — try again shortly.", NamedTextColor.RED);
    }
}
