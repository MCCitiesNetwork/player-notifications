package io.github.md5sha256.playernotifications.paper.mail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Covers {@link MailFormatting}: which tag groups a sender may use, and the send-time sanitise that
 * turns a typed message into an already-authorised MiniMessage document.
 */
class MailFormattingTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final Predicate<String> ALLOW_ALL = node -> true;
    private static final Predicate<String> DEFAULTS_ONLY = node -> MailFormatting.groups().stream()
            .filter(group -> (MailFormatting.PERMISSION_PREFIX + group.node()).equals(node))
            .findFirst()
            .map(MailFormatting.Group::defaultAllowed)
            .orElse(false);

    private static Component render(String raw, Predicate<String> permissions) {
        String stored = MailFormatting.sanitize(raw, MailFormatting.resolverFor(permissions));
        return MiniMessage.miniMessage().deserialize(stored);
    }

    @Test
    @DisplayName("a permitted tag survives the round trip as real formatting")
    void permittedTagIsFormatting() {
        Component rendered = render("<red>hello", DEFAULTS_ONLY);

        Assertions.assertEquals("hello", PLAIN.serialize(rendered));
        Assertions.assertEquals(NamedTextColor.RED, rendered.children().isEmpty()
                ? rendered.color()
                : rendered.children().get(0).color());
    }

    @Test
    @DisplayName("a denied tag is left as literal text, not stripped")
    void deniedTagStaysLiteral() {
        Component rendered = render("<click:run_command:/op me>click me</click>", DEFAULTS_ONLY);

        String text = PLAIN.serialize(rendered);
        Assertions.assertTrue(text.contains("<click:run_command:/op me>"),
                "denied tag should survive verbatim, got: " + text);
        Assertions.assertTrue(text.contains("click me"), "the sender's own words must not be dropped");
    }

    @Test
    @DisplayName("a denied click event cannot reach the rendered component")
    void deniedClickIsInert() {
        Component rendered = render("<click:run_command:/op me>click me</click>", DEFAULTS_ONLY);

        Assertions.assertNull(rendered.clickEvent());
        rendered.children().forEach(child -> Assertions.assertNull(child.clickEvent()));
    }

    @Test
    @DisplayName("the same click event is live for a sender that holds the permission")
    void permittedClickIsLive() {
        Component rendered = render("<click:run_command:/spawn>click me</click>", ALLOW_ALL);

        Assertions.assertEquals("click me", PLAIN.serialize(rendered));
        boolean anyClick = rendered.clickEvent() != null
                || rendered.children().stream().anyMatch(child -> child.clickEvent() != null);
        Assertions.assertTrue(anyClick, "an allowed click tag should produce a click event");
    }

    @Test
    @DisplayName("the default-allowed groups are exactly the cosmetic ones")
    void cosmeticGroupsAreDefaultAllowed() {
        Set<String> defaultAllowed = MailFormatting.groups().stream()
                .filter(MailFormatting.Group::defaultAllowed)
                .map(MailFormatting.Group::node)
                .collect(java.util.stream.Collectors.toSet());

        Assertions.assertEquals(
                Set.of("color", "decoration", "gradient", "rainbow", "reset", "newline"),
                defaultAllowed);
    }

    @Test
    @DisplayName("every group has a distinct node and a resolver")
    void groupsAreWellFormed() {
        List<MailFormatting.Group> groups = MailFormatting.groups();

        Assertions.assertEquals(groups.size(),
                groups.stream().map(MailFormatting.Group::node).distinct().count());
        groups.forEach(group -> Assertions.assertNotNull(group.resolver(), group.node()));
    }

    @Test
    @DisplayName("sanitising an already-sanitised message changes nothing")
    void sanitiseIsIdempotent() {
        TagResolver allowed = MailFormatting.resolverFor(DEFAULTS_ONLY);
        String once = MailFormatting.sanitize("<red>hi <click:run_command:/op me>x</click>", allowed);
        String twice = MailFormatting.sanitize(once, allowed);

        Assertions.assertEquals(once, twice);
    }

    @Test
    @DisplayName("a message of nothing but tags sanitises to blank so the caller can reject it")
    void tagOnlyMessageSanitisesToBlank() {
        String stored = MailFormatting.sanitize("<red>", MailFormatting.resolverFor(DEFAULTS_ONLY));

        Assertions.assertTrue(stored.isBlank(), "expected blank, got: " + stored);
    }

    @Test
    @DisplayName("plain text passes through unchanged")
    void plainTextIsUnchanged() {
        String stored = MailFormatting.sanitize("hello there", MailFormatting.resolverFor(DEFAULTS_ONLY));

        Assertions.assertEquals("hello there", stored);
        Assertions.assertEquals("hello there",
                PLAIN.serialize(MiniMessage.miniMessage().deserialize(stored)));
    }
}
