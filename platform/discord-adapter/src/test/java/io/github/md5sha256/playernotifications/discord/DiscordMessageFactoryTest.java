package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DiscordMessageFactoryTest {

    private static final int FALLBACK_COLOR = 0x5865F2;

    private static RenderableNotification notification(Component title, Component body) {
        return new RenderableNotification(title, body);
    }

    private static MessageEmbed onlyEmbed(MessageCreateData data) {
        Assertions.assertEquals(1, data.getEmbeds().size(), "expected exactly one embed");
        return data.getEmbeds().get(0);
    }

    @Test
    void embedTitleIsThePlainTextOfTheTitleComponent() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        MessageEmbed embed = onlyEmbed(factory.create(notification(
                Component.text("New ").append(Component.text("mail").decorate(TextDecoration.BOLD)),
                Component.text("body"))));

        Assertions.assertEquals("New mail", embed.getTitle());
    }

    @Test
    void embedDescriptionCarriesTheMarkdownBody() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        MessageEmbed embed = onlyEmbed(factory.create(notification(
                Component.text("t"),
                Component.text("hi").decorate(TextDecoration.BOLD))));

        Assertions.assertEquals("**hi**", embed.getDescription());
    }

    @Test
    void embedUsesTheTitleComponentColourWhenItHasOne() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        MessageEmbed embed = onlyEmbed(factory.create(notification(
                Component.text("t").color(TextColor.color(0x00FF00)),
                Component.text("body"))));

        Assertions.assertEquals(0x00FF00, embed.getColorRaw());
    }

    @Test
    void embedFallsBackToTheConfiguredColourWhenTheTitleHasNone() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        MessageEmbed embed = onlyEmbed(
                factory.create(notification(Component.text("t"), Component.text("body"))));

        Assertions.assertEquals(FALLBACK_COLOR, embed.getColorRaw());
    }

    @Test
    void markdownFormatEmitsBoldTitleThenBody() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.MARKDOWN, FALLBACK_COLOR);

        MessageCreateData data = factory.create(notification(
                Component.text("Title"), Component.text("hi").decorate(TextDecoration.BOLD)));

        Assertions.assertTrue(data.getEmbeds().isEmpty(), "markdown format must not build an embed");
        Assertions.assertEquals("**Title**\n**hi**", data.getContent());
    }

    @Test
    void plainFormatEmitsNoMarkdownMarkers() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.PLAIN, FALLBACK_COLOR);

        MessageCreateData data = factory.create(notification(
                Component.text("Title"), Component.text("hi").decorate(TextDecoration.BOLD)));

        Assertions.assertTrue(data.getEmbeds().isEmpty(), "plain format must not build an embed");
        Assertions.assertEquals("Title\nhi", data.getContent());
    }

    @Test
    void anOverlongEmbedTitleIsTruncatedToTheDiscordLimit() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        MessageEmbed embed = onlyEmbed(factory.create(notification(
                Component.text("t".repeat(300)), Component.text("body"))));

        Assertions.assertEquals(256, embed.getTitle().length());
        Assertions.assertTrue(embed.getTitle().endsWith("…"));
    }

    @Test
    void anOverlongEmbedDescriptionIsTruncatedToTheDiscordLimit() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        MessageEmbed embed = onlyEmbed(factory.create(notification(
                Component.text("t"), Component.text("b".repeat(5000)))));

        Assertions.assertEquals(4096, embed.getDescription().length());
        Assertions.assertTrue(embed.getDescription().endsWith("…"));
    }

    @Test
    void anOverlongPlainMessageIsTruncatedToTheDiscordLimit() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.PLAIN, FALLBACK_COLOR);

        MessageCreateData data = factory.create(
                notification(Component.text("t"), Component.text("b".repeat(3000))));

        Assertions.assertEquals(2000, data.getContent().length());
        Assertions.assertTrue(data.getContent().endsWith("…"));
    }

    @Test
    void anEmptyNotificationStillBuildsASendableEmbed() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.EMBED, FALLBACK_COLOR);

        // Discord rejects a wholly empty embed, and JDA's builder throws rather than sending one.
        MessageEmbed embed = onlyEmbed(
                factory.create(notification(Component.empty(), Component.empty())));

        Assertions.assertFalse(embed.getDescription().isEmpty());
    }

    @Test
    void anEmptyNotificationStillBuildsASendablePlainMessage() {
        DiscordMessageFactory factory =
                new DiscordMessageFactory(DiscordMessageFormat.PLAIN, FALLBACK_COLOR);

        MessageCreateData data =
                factory.create(notification(Component.empty(), Component.empty()));

        Assertions.assertFalse(data.getContent().isEmpty());
    }
}
