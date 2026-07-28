package io.github.md5sha256.playernotifications.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DiscordMarkdownSerializerTest {

    @Test
    void boldBecomesDoubleAsterisks() {
        Assertions.assertEquals(
                "**hi**",
                DiscordMarkdownSerializer.serialize(
                        Component.text("hi").decorate(TextDecoration.BOLD)));
    }

    @Test
    void italicBecomesSingleAsterisk() {
        Assertions.assertEquals(
                "*hi*",
                DiscordMarkdownSerializer.serialize(
                        Component.text("hi").decorate(TextDecoration.ITALIC)));
    }

    @Test
    void underlinedBecomesDoubleUnderscore() {
        Assertions.assertEquals(
                "__hi__",
                DiscordMarkdownSerializer.serialize(
                        Component.text("hi").decorate(TextDecoration.UNDERLINED)));
    }

    @Test
    void strikethroughBecomesDoubleTilde() {
        Assertions.assertEquals(
                "~~hi~~",
                DiscordMarkdownSerializer.serialize(
                        Component.text("hi").decorate(TextDecoration.STRIKETHROUGH)));
    }

    @Test
    void obfuscatedBecomesSpoiler() {
        Assertions.assertEquals(
                "||hi||",
                DiscordMarkdownSerializer.serialize(
                        Component.text("hi").decorate(TextDecoration.OBFUSCATED)));
    }

    @Test
    void nestedItalicInsideBoldIsWrappedIndependently() {
        Component component = Component.text("a")
                .decorate(TextDecoration.BOLD)
                .append(Component.text("b").decorate(TextDecoration.ITALIC))
                .append(Component.text("a"));

        Assertions.assertEquals("**a*b*a**", DiscordMarkdownSerializer.serialize(component));
    }

    @Test
    void markdownCharactersInLiteralTextAreEscaped() {
        Assertions.assertEquals(
                "a\\*b\\_c",
                DiscordMarkdownSerializer.serialize(Component.text("a*b_c")));
    }

    @Test
    void backslashPipeTildeBacktickAndQuoteAreEscaped() {
        Assertions.assertEquals(
                "\\\\ \\| \\~ \\` \\>",
                DiscordMarkdownSerializer.serialize(Component.text("\\ | ~ ` >")));
    }

    @Test
    void colourProducesNoMarker() {
        Assertions.assertEquals(
                "hi",
                DiscordMarkdownSerializer.serialize(
                        Component.text("hi").color(NamedTextColor.RED)));
    }

    @Test
    void newlinesArePreserved() {
        Component component = Component.text("a")
                .append(Component.newline())
                .append(Component.text("b"));

        Assertions.assertEquals("a\nb", DiscordMarkdownSerializer.serialize(component));
    }

    @Test
    void decorationNegatedInsideDecoratedParentClosesAndReopensTheRun() {
        Component component = Component.text("a")
                .decorate(TextDecoration.BOLD)
                .append(Component.text("b").decoration(TextDecoration.BOLD, TextDecoration.State.FALSE))
                .append(Component.text("c"));

        Assertions.assertEquals("**a**b**c**", DiscordMarkdownSerializer.serialize(component));
    }

    @Test
    void emptyComponentSerializesToEmptyString() {
        Assertions.assertEquals("", DiscordMarkdownSerializer.serialize(Component.empty()));
    }
}
