package io.github.md5sha256.playernotifications.paper.customtype;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the notification registry in step with {@code notification-types.yml} — the only class that
 * registers or withdraws an operator-declared type.
 *
 * <p>{@link #sync()} runs at the end of {@code onEnable} (after {@code startModules()}, so a module's
 * claim on a key is already visible) and again on {@code /notifications reload}. It diffs the declared
 * keys against the ones it registered last time, so a reload adds what appeared, withdraws what was
 * deleted, and touches nothing else.
 *
 * <h2>Two rules worth stating</h2>
 *
 * <p><b>Withdrawal goes through {@link NotificationDataTypeRegistry#unmapDataType(String)}, never
 * {@code unregisterPayloadMapping}.</b> The latter cascades to the payload class's processor,
 * serializer and renderer — and every declared type shares {@link CustomNotificationPayload}, so
 * withdrawing one type that way would break all the others.
 *
 * <p><b>A key already mapped to some other payload class is skipped, with a warning naming it.</b>
 * Re-registering {@code mail} against {@link CustomNotificationPayload} would silently replace the
 * mapping the mail feature depends on. A skipped key is never recorded as ours, so a later sync that
 * no longer declares it cannot withdraw someone else's mapping either.
 *
 * <p>No processor is registered alongside the renderer, deliberately: an explicitly registered
 * processor wins dispatch and would bypass preferences and sinks entirely. That is right for
 * {@code mail} and wrong here — a declared type exists precisely so a player can choose where it
 * reaches them.
 */
public final class CustomTypeRegistrar {

    private final NotificationService service;
    private final CustomNotificationTypes types;
    private final Logger logger;

    /** Only the keys this class actually registered, so nothing else can be withdrawn by mistake. */
    private final Set<String> registered = new HashSet<>();

    public CustomTypeRegistrar(@NotNull NotificationService service,
                               @NotNull CustomNotificationTypes types,
                               @NotNull Logger logger) {
        this.service = service;
        this.types = types;
        this.logger = logger;
    }

    /** Registers newly declared types, withdraws deleted ones, and leaves the rest alone. */
    public void sync() {
        NotificationDataTypeRegistry registry = this.service.dataTypeRegistry();
        CustomTypeRenderer renderer = new CustomTypeRenderer(this.types);
        Set<String> desired = this.types.keys();

        for (String key : Set.copyOf(this.registered)) {
            if (!desired.contains(key)) {
                registry.unmapDataType(key);
                registry.unregisterDisplayName(key);
                this.registered.remove(key);
            }
        }

        for (String key : desired) {
            if (this.registered.contains(key)) {
                // Already ours; a changed title needs no re-registration, since the renderer reads
                // the declaration on every render.
                continue;
            }
            Optional<Class<?>> existing = registry.resolvePayloadClass(key);
            if (existing.isPresent()) {
                this.logger.log(Level.WARNING, "Ignoring notification type '" + key
                        + "' in notification-types.yml: that type is already registered by the plugin"
                        + " or by a module (" + existing.get().getName() + ")");
                continue;
            }
            this.service.registerJsonRenderable(key, CustomNotificationPayload.class, renderer);
            this.types.get(key)
                    .map(DeclaredNotificationType::displayName)
                    .ifPresent(displayName -> registry.registerDisplayName(key, displayName));
            this.registered.add(key);
        }
    }
}
