package io.github.md5sha256.playernotifications.api;

import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class NotificationDataTypeRegistry {

    private final Map<Class<?>, NotificationProcessor<?>> processors
            = Collections.synchronizedMap(new HashMap<>());
    private final Map<Class<?>, PayloadSerializer<?>> serializers
            = Collections.synchronizedMap(new HashMap<>());
    private final Map<Class<?>, NotificationRenderer<?>> renderers
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

    public <T> void registerSerializer(@NotNull Class<T> payloadClass,
                                       @NotNull PayloadSerializer<T> serializer) {
        this.serializers.put(payloadClass, serializer);
    }

    public <T> void registerRenderer(@NotNull Class<T> payloadClass,
                                     @NotNull NotificationRenderer<T> renderer) {
        this.renderers.put(payloadClass, renderer);
    }

    public void unregisterPayloadMapping(@NotNull String dataType) {
        Class<?> payloadClass = this.payloadMapping.remove(dataType);
        if (payloadClass != null) {
            unregisterProcessor(payloadClass);
            unregisterSerializer(payloadClass);
            unregisterRenderer(payloadClass);
        }
    }

    public void unregisterProcessor(@NotNull Class<?> payloadClass) {
        this.processors.remove(payloadClass);
    }

    public void unregisterSerializer(@NotNull Class<?> payloadClass) {
        this.serializers.remove(payloadClass);
    }

    public void unregisterRenderer(@NotNull Class<?> payloadClass) {
        this.renderers.remove(payloadClass);
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
    @SuppressWarnings("unchecked")
    public <T> Optional<PayloadSerializer<T>> getSerializer(@NotNull Class<T> payloadClass) {
        var rawSerializer = this.serializers.get(payloadClass);
        if (rawSerializer == null) {
            return Optional.empty();
        }
        return Optional.of((PayloadSerializer<T>) rawSerializer);
    }

    @NotNull
    public Optional<? extends PayloadSerializer<?>> getSerializer(@NotNull String dataType) {
        return resolvePayloadClass(dataType).flatMap(this::getSerializer);
    }

    @NotNull
    @SuppressWarnings("unchecked")
    public <T> Optional<NotificationRenderer<T>> getRenderer(@NotNull Class<T> payloadClass) {
        var rawRenderer = this.renderers.get(payloadClass);
        if (rawRenderer == null) {
            return Optional.empty();
        }
        return Optional.of((NotificationRenderer<T>) rawRenderer);
    }

    @NotNull
    public Optional<? extends NotificationRenderer<?>> getRenderer(@NotNull String dataType) {
        return resolvePayloadClass(dataType).flatMap(this::getRenderer);
    }

    @NotNull
    public Optional<Class<?>> resolvePayloadClass(@NotNull String dataType) {
        return Optional.ofNullable(this.payloadMapping.get(dataType));
    }

    /**
     * Every {@code dataType} with a registered payload mapping.
     */
    @NotNull
    public Set<String> dataTypes() {
        return Set.copyOf(this.payloadMapping.keySet());
    }

}
