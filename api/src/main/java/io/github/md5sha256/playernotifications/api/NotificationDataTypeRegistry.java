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
    /** Raw MiniMessage, keyed by {@code dataType}. See {@link #registerDisplayName}. */
    private final Map<String, String> displayNames = Collections.synchronizedMap(new HashMap<>());

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

    /**
     * Removes <b>only</b> the {@code dataType} → payload class mapping, leaving that class's
     * processor, serializer and renderer registered.
     *
     * <p>The counterpart to {@link #unregisterPayloadMapping(String)}, which cascades. Cascading is
     * wrong wherever <em>several</em> data types share one payload class — the host's
     * operator-declared types do, because renderers are keyed by class and there can be no class per
     * key in a file read at runtime. Tearing the shared renderer down to remove one key would break
     * every other key mapped to it.
     *
     * <p>A {@code dataType} with no mapping is a no-op.
     */
    public void unmapDataType(@NotNull String dataType) {
        this.payloadMapping.remove(dataType);
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

    /**
     * Supplies the player-facing name for a {@code dataType} — the module author's answer to "what is
     * this type called", which the preference screens show instead of guessing from the registry key.
     *
     * <p>Registering one is <b>optional</b>. A type with no display name is title-cased from its key
     * ({@code essentials-mail} → "Essentials Mail"), which is what every type did before this existed.
     * Supply one whenever the key is an identifier rather than a name.
     *
     * <p>{@code displayName} is raw <b>MiniMessage</b>, so a name may carry colour. It is a
     * <em>default</em>, not the final word: a server operator overrides it in the host's
     * {@code type-names.yml}, and their value always wins. Nothing here is persisted — re-register on
     * every startup, as with every other registration on this class.
     */
    public void registerDisplayName(@NotNull String dataType, @NotNull String displayName) {
        this.displayNames.put(dataType, displayName);
    }

    public void unregisterDisplayName(@NotNull String dataType) {
        this.displayNames.remove(dataType);
    }

    /**
     * The module-supplied display name for a {@code dataType}, or empty when none was registered.
     *
     * <p>Deliberately independent of {@link #registerPayloadMapping}: naming a type does not make it a
     * known type, so {@link #dataTypes()} still reports only types with a payload mapping. A name for a
     * type nothing registers is inert rather than conjuring a row into the preference screens.
     */
    @NotNull
    public Optional<String> displayName(@NotNull String dataType) {
        return Optional.ofNullable(this.displayNames.get(dataType));
    }

}
