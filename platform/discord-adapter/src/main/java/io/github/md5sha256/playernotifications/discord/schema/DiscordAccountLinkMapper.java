package io.github.md5sha256.playernotifications.discord.schema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code DiscordAccountLink} table, which holds at most
 * one row per player and at most one row per Discord id. SQL annotations are supplied by
 * database-specific sub-interfaces.
 *
 * <p>There is deliberately no update operation: a re-link is expressed as delete-then-insert by the
 * caller, because a replacement has to clear the row for the <em>old</em> Discord id as well as the one
 * for the player, and no single statement covers both sides of a two-way unique relation.
 */
public interface DiscordAccountLinkMapper {

    /** The player's link, or {@code null} if they have none. */
    @Nullable DiscordAccountLinkEntity selectByPlayer(@NotNull UUID playerUuid);

    /** The link for a Discord user, or {@code null} if that account is not linked. */
    @Nullable DiscordAccountLinkEntity selectByDiscordId(long discordId);

    /**
     * Inserts a link. Fails on the primary key if the player is already linked, and on the unique index
     * if the Discord id is — callers replacing a link must delete both sides first.
     *
     * @return the number of rows inserted
     */
    int insertLink(@NotNull UUID playerUuid, long discordId, @NotNull Instant linkedAt);

    /**
     * Deletes the given player's link, if any.
     *
     * @return the number of rows removed, so a caller can report whether a link actually existed
     */
    int deleteByPlayer(@NotNull UUID playerUuid);

    /**
     * Deletes the link for the given Discord user, if any.
     *
     * @return the number of rows removed
     */
    int deleteByDiscordId(long discordId);

}
