package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reproduces the Bukkit/Paper classloader topology to answer: can a payload POJO defined by a
 * third-party plugin that loads <em>after</em> PlayerNotifications be (de)serialized by the shared
 * Jackson {@link com.fasterxml.jackson.databind.ObjectMapper}?
 *
 * <p>In Bukkit each plugin has its own class loader. A downstream plugin's loader can see this
 * plugin's classes (and its shaded Jackson), but this plugin's loader — which built the ObjectMapper
 * — cannot see the downstream plugin's classes. We model that by compiling a POJO into a
 * <b>child</b> {@link URLClassLoader} whose parent is the service/Jackson class loader: the child can
 * see Jackson, the parent cannot see the POJO by name. If Jackson ever resolved the payload type by
 * name (rather than via the {@link Class} object handed to it), this test would fail with
 * {@link ClassNotFoundException}.
 */
class ThirdPartyPayloadClassLoaderTest extends AbstractDatabaseTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    private static final String POJO_FQCN = "thirdparty.pojo.PlayerScore";
    private static final String POJO_SOURCE = """
            package thirdparty.pojo;

            public class PlayerScore {
                private String player;
                private int score;

                public PlayerScore() {}

                public PlayerScore(String player, int score) {
                    this.player = player;
                    this.score = score;
                }

                public String getPlayer() { return player; }
                public void setPlayer(String player) { this.player = player; }
                public int getScore() { return score; }
                public void setScore(int score) { this.score = score; }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a POJO from a later-loaded plugin's class loader round-trips through enqueue and delivery")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pojoFromChildClassLoaderRoundTrips() throws Exception {
        // The service (and its ObjectMapper) live in the parent loader; the POJO lives only in the
        // child, invisible to the parent — exactly like a downstream plugin loaded after this one.
        ClassLoader serviceLoader = DefaultNotificationService.class.getClassLoader();
        ClassLoader pluginLoader = compileToChildLoader(serviceLoader);
        Assertions.assertThrows(ClassNotFoundException.class, () -> serviceLoader.loadClass(POJO_FQCN),
                "precondition: the service's loader must NOT see the POJO, or the test proves nothing");

        Class<?> pojoClass = pluginLoader.loadClass(POJO_FQCN);
        Object payload = pojoClass.getConstructor(String.class, int.class).newInstance("Steve", 42);

        List<Object> received = new ArrayList<>();
        NotificationProcessor processor = (p, target) -> {
            received.add(p);
            return NotificationDisposition.MARK_SEEN;
        };
        service.registerJsonPayload("player-score", (Class) pojoClass, processor);

        // Force the thread context class loader to the parent too, so the POJO is unreachable via
        // BOTH the mapper's loader and the TCCL — the worst case for a real delivery thread. If the
        // round-trip still works, it is because Jackson uses the Class object, not name resolution.
        Thread current = Thread.currentThread();
        ClassLoader originalTccl = current.getContextClassLoader();
        current.setContextClassLoader(serviceLoader);
        try {
            service.enqueueNotification(new TypedNotification<>(
                    "ps1", DUE, null, new NotificationTarget(List.of(PLAYER)), "player-score", payload, 0), false);

            new NotificationDelivery(database, service.dataTypeRegistry(), Logger.getLogger("test"))
                    .deliver(PLAYER, NOW);
        } finally {
            current.setContextClassLoader(originalTccl);
        }

        Assertions.assertEquals(1, received.size(), "processor should receive exactly one payload");
        Object delivered = received.get(0);
        Assertions.assertSame(pojoClass, delivered.getClass(),
                "delivered payload must be the third-party POJO type, deserialized by the shared mapper");
        Assertions.assertEquals("Steve", pojoClass.getMethod("getPlayer").invoke(delivered));
        Assertions.assertEquals(42, pojoClass.getMethod("getScore").invoke(delivered));
    }

    /**
     * Compiles {@link #POJO_SOURCE} into a fresh directory and returns a child {@link URLClassLoader}
     * that loads it, delegating everything else to {@code parent}.
     */
    private ClassLoader compileToChildLoader(ClassLoader parent) throws Exception {
        Path srcFile = tempDir.resolve("src/" + POJO_FQCN.replace('.', '/') + ".java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, POJO_SOURCE);

        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, "a JDK (not JRE) is required to run this test");
        int rc = compiler.run(null, null, null, "-d", out.toString(), srcFile.toString());
        Assertions.assertEquals(0, rc, "third-party POJO failed to compile");

        return new URLClassLoader(new URL[]{out.toUri().toURL()}, parent);
    }
}
