package io.github.md5sha256.playernotifications.paper.customtype;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link CustomTypeRenderer}, the single renderer every operator-declared type shares.
 *
 * <p>The case that matters most is the last one: the title is resolved from the declaration on every
 * render, so an operator who edits a title and reloads changes how notifications already sitting in
 * inboxes read.
 */
class CustomTypeRendererTest {

    private static final UUID TARGET = UUID.randomUUID();

    private CustomNotificationTypes types;
    private CustomTypeRenderer renderer;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("CustomTypeRendererTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        this.types = new CustomNotificationTypes(logger);
        this.types.load(declaring("restart-warning", "<red>Server Restart"));
        this.renderer = new CustomTypeRenderer(this.types);
    }

    private static BasicConfigurationNode declaring(String key, String title) {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        try {
            root.node(key).node("title").set(title);
        } catch (SerializationException ex) {
            throw new AssertionError(ex);
        }
        return root;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("the title comes from the declaration, with its formatting")
    void titleComesFromTheDeclaration() {
        RenderableNotification rendered = this.renderer.render(
                new CustomNotificationPayload("restart-warning", "in 5 minutes"), TARGET);

        assertEquals(MiniMessage.miniMessage().deserialize("<red>Server Restart"), rendered.title());
        assertFalse(rendered.title().style().isEmpty(), "the red should survive into the component");
    }

    @Test
    @DisplayName("the body is the sender's message, parsed as MiniMessage")
    void bodyIsParsedAsMiniMessage() {
        RenderableNotification rendered = this.renderer.render(
                new CustomNotificationPayload("restart-warning", "<gold>in 5 minutes"), TARGET);

        assertEquals(MiniMessage.miniMessage().deserialize("<gold>in 5 minutes"), rendered.body());
    }

    @Test
    @DisplayName("a legacy section sign in the body falls back to literal text")
    void malformedBodyFallsBackToLiteralText() {
        RenderableNotification rendered = this.renderer.render(
                new CustomNotificationPayload("restart-warning", "§cin 5 minutes"), TARGET);

        assertEquals(Component.text("§cin 5 minutes"), rendered.body());
    }

    @Test
    @DisplayName("an undeclared type still renders, titled with its title-cased key")
    void undeclaredTypeFallsBackToTheTitleCasedKey() {
        RenderableNotification rendered = this.renderer.render(
                new CustomNotificationPayload("restart-warning", "in 5 minutes"), TARGET);
        assertEquals("Server Restart", plain(rendered.title()));

        this.types.load(BasicConfigurationNode.root());
        rendered = this.renderer.render(
                new CustomNotificationPayload("restart-warning", "in 5 minutes"), TARGET);

        assertEquals("Restart Warning", plain(rendered.title()));
        assertEquals("in 5 minutes", plain(rendered.body()));
    }

    @Test
    @DisplayName("an edited title reaches an already-stored payload")
    void anEditedTitleReachesAStoredPayload() {
        CustomNotificationPayload payload =
                new CustomNotificationPayload("restart-warning", "in 5 minutes");
        assertEquals("Server Restart", plain(this.renderer.render(payload, TARGET).title()));

        this.types.load(declaring("restart-warning", "<red>Restarting Soon"));

        assertEquals("Restarting Soon", plain(this.renderer.render(payload, TARGET).title()));
    }

    @Test
    @DisplayName("a declared type reads the same to everyone")
    void targetIsIgnored() {
        CustomNotificationPayload payload =
                new CustomNotificationPayload("restart-warning", "in 5 minutes");

        assertEquals(this.renderer.render(payload, UUID.randomUUID()),
                this.renderer.render(payload, UUID.randomUUID()));
    }
}
