-- Discord account links, owned by the Discord adapter module.
--
-- This module owns its own schema: this script, DiscordSchemaMigrator, and the discord_schema_version
-- table that tracks it. It reuses the host's connection pool, MyBatis session factory and UUID type
-- handler, but core contains no Discord-shaped type, table or migration — a server with no Discord module
-- has no DiscordAccountLink table at all.
--
-- playerUuid is the primary key and discordId is uniquely indexed, so the relation is one-to-one in
-- both directions: a player cannot link two Discord accounts, and one Discord account cannot receive
-- notifications for two players. Enforcing this in the schema means a concurrent double-redeem fails
-- at the constraint rather than producing a duplicate.
--
-- discordId is a signed BIGINT, matching the long that DiscordAccountProvider#discordIdFor returns.
-- Discord snowflakes are unsigned 64-bit in principle, but their timestamp field puts real ids far
-- below 2^63, so an unsigned column would only force a BigInteger mapping for no present benefit.
--
-- linkedAt exists for operator diagnostics ("when did this link happen") and is read by nothing in
-- code, so it is deliberately not indexed.
--
-- Single statement: DiscordSchemaMigrator splits scripts on ';', so a trigger or procedure body cannot be
-- expressed here. Core has the same constraint for the same reason.
CREATE TABLE IF NOT EXISTS DiscordAccountLink
(
    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
    discordId  BIGINT     NOT NULL,
    linkedAt   DATETIME   NOT NULL,
    UNIQUE KEY uk_discord_account_link_discord_id (discordId)
);
