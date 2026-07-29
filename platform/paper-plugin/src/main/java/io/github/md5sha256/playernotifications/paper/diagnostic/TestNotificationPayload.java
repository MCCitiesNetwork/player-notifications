package io.github.md5sha256.playernotifications.paper.diagnostic;

import org.jetbrains.annotations.NotNull;

/**
 * The payload for the built-in {@code test} data type, sent by {@code /notifications test}.
 *
 * <p>Its own record rather than {@link String}: the registry keys serializers and renderers by payload
 * class, so a shared class means shared handlers.
 *
 * @param message the operator-supplied text to echo through the renderer
 */
public record TestNotificationPayload(@NotNull String message) {

    /**
     * The registered {@code dataType} for this payload. Players configure delivery media against this
     * key, exactly as for any module-supplied type.
     */
    public static final String TEST_DATA_TYPE = "test";
}
