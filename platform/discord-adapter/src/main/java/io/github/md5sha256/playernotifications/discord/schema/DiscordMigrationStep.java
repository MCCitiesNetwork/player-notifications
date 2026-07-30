package io.github.md5sha256.playernotifications.discord.schema;

import org.jetbrains.annotations.NotNull;

/**
 * A single ordered migration in <em>this module's</em> schema chain: its {@code version}, a human-readable
 * {@code description}, and the classpath-relative {@code resourcePath} of the SQL script, resolved against
 * the module jar.
 *
 * <p>Deliberately a duplicate of core's {@code MigrationStep} rather than a reuse of it. Reusing core's
 * would put a core type in this module's own migrator signature, reintroducing — in the opposite direction
 * — exactly the coupling that moving this schema out of core removed, for the sake of three fields.
 *
 * @param version      the chain position; must be unique and is recorded once applied
 * @param description  what the step does, logged and stored for operator diagnostics
 * @param resourcePath the script's path inside this module's jar, e.g. {@code sql/discord/V1__foo.sql}
 */
public record DiscordMigrationStep(int version, @NotNull String description, @NotNull String resourcePath) {
}
