CREATE TABLE IF NOT EXISTS PlayerNotificationPreference
(
    playerUuid BINARY(16)  NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, medium)
);
