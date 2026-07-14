package io.github.md5sha256.playernotifications.api.serialize;

/**
 * Thrown when a {@link PayloadSerializer} fails to serialize or deserialize a payload, or when a
 * typed enqueue finds no serializer registered for its data type.
 */
public class PayloadSerializationException extends RuntimeException {

    public PayloadSerializationException(String message) {
        super(message);
    }

    public PayloadSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}