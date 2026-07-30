package io.github.md5sha256.playernotifications.discord;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link DiscordAccountLinkStore} for tests, reproducing the two-way uniqueness the real
 * table enforces: {@link #link} clears any row for the player <em>and</em> any row for the Discord id
 * before inserting, so a fake that accepts a re-link cannot hide a production constraint violation.
 */
final class FakeDiscordAccountLinkStore implements DiscordAccountLinkStore {

    private final Map<UUID, Long> byPlayer = new HashMap<>();

    @Override
    public Optional<Long> discordIdFor(UUID playerUuid) {
        return Optional.ofNullable(this.byPlayer.get(playerUuid));
    }

    @Override
    public Optional<UUID> playerFor(long discordId) {
        return this.byPlayer.entrySet().stream()
                .filter(entry -> entry.getValue() == discordId)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    @Override
    public void link(UUID playerUuid, long discordId) {
        this.byPlayer.remove(playerUuid);
        this.byPlayer.values().removeIf(existing -> existing == discordId);
        this.byPlayer.put(playerUuid, discordId);
    }

    @Override
    public boolean unlink(UUID playerUuid) {
        return this.byPlayer.remove(playerUuid) != null;
    }
}
