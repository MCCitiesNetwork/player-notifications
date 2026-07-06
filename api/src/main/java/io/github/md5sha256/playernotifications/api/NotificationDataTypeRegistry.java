package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class NotificationDataTypeRegistry {

    private final Map<Class<?>, NotificationProcessor<?>> processors
            = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Class<?>> payloadMapping = Collections.synchronizedMap(new HashMap<>());

    public <T> void registerPayloadMapping(@NotNull String dataType,
                                           @NotNull Class<T> payloadClass) {
        this.payloadMapping.put(dataType, payloadClass);
    }

    public <T> void registerProcessor(@NotNull Class<T> payloadClass,
                                      @NotNull NotificationProcessor<T> processor) {
        this.processors.put(payloadClass, processor);
    }

    @NotNull
    @SuppressWarnings("unchecked")
    public <T> Optional<NotificationProcessor<T>> getProcessor(@NotNull Class<T> payloadClass) {
        var rawProcessor = this.processors.get(payloadClass);
        if (rawProcessor == null) {
            return Optional.empty();
        }
        return Optional.of((NotificationProcessor<T>) rawProcessor);
    }

    @NotNull
    public Optional<? extends NotificationProcessor<?>> getProcessor(@NotNull String dataType) {
        return resolvePayloadClass(dataType).flatMap(this::getProcessor);
    }

    @NotNull
    public Optional<Class<?>> resolvePayloadClass(@NotNull String dataType) {
        return Optional.ofNullable(this.payloadMapping.get(dataType));
    }

}
