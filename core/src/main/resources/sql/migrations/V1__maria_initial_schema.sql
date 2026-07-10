CREATE TABLE IF NOT EXISTS NotificationTarget
(
    notifTargetId INT        NOT NULL,
    playerUuid    BINARY(16) NOT NULL,
    PRIMARY KEY (notifTargetId, playerUuid)
);

CREATE TABLE IF NOT EXISTS Notification
(
    notifKey           VARCHAR(255) NOT NULL PRIMARY KEY,
    notifScheduledTime DATETIME     NOT NULL,
    notifExpiryTime    DATETIME     NULL,
    notifTargetId      INT          NOT NULL,
    notifPayloadType   VARCHAR(255) NOT NULL,
    notifPayload       JSON         NOT NULL,
    notifPriority      INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_notification_target ON Notification (notifTargetId);

CREATE INDEX idx_notification_payload_type ON Notification (notifPayloadType);

CREATE INDEX idx_notification_scheduled_time ON Notification (notifScheduledTime);

CREATE INDEX idx_notification_expiry_time ON Notification (notifExpiryTime);

-- When the last member of a target group is removed, the notifications that point at that group
-- have no remaining audience, so delete them. Single-statement trigger body (no BEGIN...END) so the
-- schema migrator, which splits the script on ';', executes it as one statement.
CREATE TRIGGER trg_delete_targetless_notification
    AFTER DELETE ON NotificationTarget
    FOR EACH ROW
    DELETE FROM Notification
    WHERE notifTargetId = OLD.notifTargetId
      AND NOT EXISTS(SELECT 1 FROM NotificationTarget WHERE notifTargetId = OLD.notifTargetId);
