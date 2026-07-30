-- Discord account links owned by this plugin, backing the Discord adapter's "embedded" link provider.
--
-- This table lives in core rather than in platform:discord-adapter because a feature module cannot own
-- a migration today: MariaSchemaMigrator tracks one schema_version chain from a hardcoded step list,
-- and MariaDatabase registers its mappers from a hardcoded list too.
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
CREATE TABLE IF NOT EXISTS DiscordAccountLink
(
    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
    discordId  BIGINT     NOT NULL,
    linkedAt   DATETIME   NOT NULL,
    UNIQUE KEY uk_discord_account_link_discord_id (discordId)
);
