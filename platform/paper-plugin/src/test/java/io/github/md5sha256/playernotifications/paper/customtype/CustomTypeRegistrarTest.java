package io.github.md5sha256.playernotifications.paper.customtype;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CustomTypeRegistrar}: what a reload adds, what it withdraws, and — the case
 * the whole design turns on — that withdrawing one declared type leaves the renderer every other
 * declared type shares registered.
 */
class CustomTypeRegistrarTest {

    private FakeService service;
    private CustomNotificationTypes types;
    private CustomTypeRegistrar registrar;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("CustomTypeRegistrarTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        this.service = new FakeService();
        this.types = new CustomNotificationTypes(logger);
        this.registrar = new CustomTypeRegistrar(this.service, this.types, logger);
    }

    private NotificationDataTypeRegistry registry() {
        return this.service.registry;
    }

    /** A {@code notification-types.yml} root declaring each key with a title and optional name. */
    private static ConfigurationNode declaring(Map<String, String> keysToDisplayNames) {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        keysToDisplayNames.forEach((key, displayName) -> {
            try {
                root.node(key).node("title").set("<red>" + key);
                if (displayName != null) {
                    root.node(key).node("display-name").set(displayName);
                }
            } catch (SerializationException ex) {
                throw new AssertionError(ex);
            }
        });
        return root;
    }

    private static Map<String, String> declarations(String... keys) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : keys) {
            map.put(key, null);
        }
        return map;
    }

    @Test
    @DisplayName("a first sync registers every declared type as a renderable")
    void firstSyncRegistersEveryDeclaredType() {
        this.types.load(declaring(declarations("restart-warning", "event")));

        this.registrar.sync();

        assertEquals(Set.of("restart-warning", "event"), registry().dataTypes());
        assertEquals(List.of("event", "restart-warning"),
                this.service.registeredRenderables.stream().sorted().toList());
        assertTrue(registry().getRenderer(CustomNotificationPayload.class).isPresent());
    }

    @Test
    @DisplayName("a declared display name is registered, and an absent one leaves none")
    void displayNamesAreRegistered() {
        Map<String, String> declarations = declarations("restart-warning");
        declarations.put("restart-warning", "<red>Restart Warnings");
        declarations.put("event", null);
        this.types.load(declaring(declarations));

        this.registrar.sync();

        assertEquals(Optional.of("<red>Restart Warnings"), registry().displayName("restart-warning"));
        assertTrue(registry().displayName("event").isEmpty());
    }

    @Test
    @DisplayName("a withdrawn type is unmapped, and the shared renderer survives")
    void aWithdrawnTypeIsUnmappedAndTheSharedRendererSurvives() {
        this.types.load(declaring(declarations("restart-warning", "event")));
        this.registrar.sync();

        this.types.load(declaring(declarations("event")));
        this.registrar.sync();

        assertEquals(Set.of("event"), registry().dataTypes());
        assertTrue(registry().displayName("restart-warning").isEmpty());
        assertTrue(registry().getRenderer(CustomNotificationPayload.class).isPresent(),
                "removing one declared type must not tear down the renderer the others share");
    }

    @Test
    @DisplayName("a key already mapped elsewhere is skipped and never unmapped")
    void aKeyMappedElsewhereIsSkippedAndNeverUnmapped() {
        registry().registerPayloadMapping("mail", String.class);
        this.types.load(declaring(declarations("mail", "event")));

        this.registrar.sync();

        assertEquals(Optional.of(String.class), registry().resolvePayloadClass("mail"));
        assertFalse(this.service.registeredRenderables.contains("mail"));

        // A later sync that no longer declares it must not withdraw someone else's mapping either.
        this.types.load(declaring(declarations("event")));
        this.registrar.sync();

        assertEquals(Optional.of(String.class), registry().resolvePayloadClass("mail"));
    }

    @Test
    @DisplayName("re-syncing an unchanged file registers nothing new")
    void reSyncingAnUnchangedFileIsIdempotent() {
        this.types.load(declaring(declarations("event")));
        this.registrar.sync();
        this.registrar.sync();

        assertEquals(List.of("event"), this.service.registeredRenderables);
        assertEquals(Set.of("event"), registry().dataTypes());
    }

    /**
     * Only the two registry-facing calls do anything; everything else on the interface throws, as the
     * fakes in {@code PersistentBroadcasterTest} and {@code MailSenderTest} do. The registry is real,
     * because the mapping and renderer bookkeeping is exactly what is under test.
     */
    private static final class FakeService implements NotificationService {

        private final NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        private final List<String> registeredRenderables = new ArrayList<>();

        @Override
        public <T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                                               @NotNull NotificationRenderer<T> renderer) {
            this.registeredRenderables.add(dataType);
            this.registry.registerPayloadMapping(dataType, type);
            this.registry.registerRenderer(type, renderer);
        }

        @Override
        public @NotNull NotificationDataTypeRegistry dataTypeRegistry() {
            return this.registry;
        }

        @Override
        public void enqueueNotification(@NotNull ResolvedNotification notification,
                                        boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void enqueueNotification(@NotNull TypedNotification<T> notification,
                                            boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                                            @NotNull NotificationProcessor<T> processor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull List<ResolvedNotification> resolveNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotification(@NotNull String notificationKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTarget(@NotNull String notificationKey, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTargets(@NotNull String notificationKey,
                                              @NotNull Collection<UUID> playerIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull String notificationDataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearExpiredNotifications() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize,
                                        @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull Map<String, Integer> unreadCountsByDataType(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int unreadCount(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSeen(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markUnread(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAllSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dismissSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void pruneOrphanedTargets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationCategoryRegistry categoryRegistry() {
            throw new UnsupportedOperationException();
        }
    }
}
