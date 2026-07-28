CREATE TABLE PlayerNotificationPreference_v3
(
    playerUuid BINARY(16)  NOT NULL,
    category   VARCHAR(64) NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, category, medium)
);

INSERT INTO PlayerNotificationPreference_v3 (playerUuid, category, medium)
SELECT playerUuid, '*', medium
FROM PlayerNotificationPreference;

DROP TABLE PlayerNotificationPreference;

RENAME TABLE PlayerNotificationPreference_v3 TO PlayerNotificationPreference;
