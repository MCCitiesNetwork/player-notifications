CREATE TABLE IF NOT EXISTS PlayerNotificationMute
(
    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
    mutedTime  DATETIME   NOT NULL
);
