package io.github.md5sha256.playernotifications.discord.schema;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal entity record mapping one-to-one to a row of the {@code DiscordAccountLink} DDL table — a
 * verified link between a Minecraft player and a Discord user, written by this module's link flow and read
 * by its {@code embedded} account-link provider.
 *
 * <p>Owned by this module, not by {@code core}: the table, its migration and its mapper all live here, and
 * only the connection pool is borrowed from the host. See {@link DiscordSchemaMigrator}.
 *
 * <p>The relation is one-to-one in both directions; see the migration script for why that is enforced
 * in the schema rather than in application code.
 *
 * @param playerUuid the linked player; the primary key
 * @param discordId  the linked Discord user id (a snowflake), uniquely indexed
 * @param linkedAt   when the link was created, kept for operator diagnostics only
 */
public record DiscordAccountLinkEntity(
        @NotNull UUID playerUuid,
        long discordId,
        @NotNull Instant linkedAt
) {
}
