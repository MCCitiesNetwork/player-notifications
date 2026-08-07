ALTER TABLE NotificationTarget ADD COLUMN IF NOT EXISTS seenTime DATETIME NULL;
CREATE INDEX IF NOT EXISTS idx_target_player_seen ON NotificationTarget (playerUuid, seenTime);
