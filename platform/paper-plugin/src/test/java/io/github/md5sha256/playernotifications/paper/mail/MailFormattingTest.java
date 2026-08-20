package io.github.md5sha256.playernotifications.paper.mail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Covers {@link MailFormatting}: which tag groups a sender may use, and the send-time sanitise that
 * turns a typed message into an already-authorised MiniMessage document.
 */
class MailFormattingTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * The cosmetic groups, which a server is expected to grant together. Nothing is granted by
     * default — every node is op — so the tests name this set rather than deriving it from the code
     * under test.
     */
    private static final Set<String> COSMETIC =
            Set.of("color", "decoration", "gradient", "rainbow", "reset", "newline");

    private static final Predicate<String> ALLOW_ALL = node -> true;
    private static final Predicate<String> COSMETIC_ONLY = node ->
            node.startsWith(MailFormatting.PERMISSION_PREFIX)
                    && COSMETIC.contains(node.substring(MailFormatting.PERMISSION_PREFIX.length()));
    private static final Predicate<String> DENY_ALL = node -> false;

    private static Component render(String raw, Predicate<String> permissions) {
        String stored = MailFormatting.sanitize(raw, MailFormatting.resolverFor(permissions));
        return MiniMessage.miniMessage().deserialize(stored);
    }

    @Test
    @DisplayName("a permitted tag survives the round trip as real formatting")
    void permittedTagIsFormatting() {
        Component rendered = render("<red>hello", COSMETIC_ONLY);

        Assertions.assertEquals("hello", PLAIN.serialize(rendered));
        Assertions.assertEquals(NamedTextColor.RED, rendered.children().isEmpty()
                ? rendered.color()
                : rendered.children().get(0).color());
    }

    @Test
    @DisplayName("a denied tag is left as literal text, not stripped")
    void deniedTagStaysLiteral() {
        Component rendered = render("<click:run_command:/op me>click me</click>", COSMETIC_ONLY);

        String text = PLAIN.serialize(rendered);
        Assertions.assertTrue(text.contains("<click:run_command:/op me>"),
                "denied tag should survive verbatim, got: " + text);
        Assertions.assertTrue(text.contains("click me"), "the sender's own words must not be dropped");
    }

    @Test
    @DisplayName("a denied click event cannot reach the rendered component")
    void deniedClickIsInert() {
        Component rendered = render("<click:run_command:/op me>click me</click>", COSMETIC_ONLY);

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
    @DisplayName("every group is declared in paper-plugin.yml, and every one of them is op")
    void everyGroupIsDeclaredAsOp() throws Exception {
        String descriptor;
        try (InputStream in = MailFormattingTest.class.getResourceAsStream("/paper-plugin.yml")) {
            Assertions.assertNotNull(in, "paper-plugin.yml should be on the test classpath");
            descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (MailFormatting.Group group : MailFormatting.groups()) {
            int at = descriptor.indexOf("  " + MailFormatting.PERMISSION_PREFIX + group.node() + ":");
            Assertions.assertTrue(at >= 0, "undeclared permission node for group " + group.node());
            int defaultAt = descriptor.indexOf("default:", at);
            String setting = descriptor.substring(defaultAt, descriptor.indexOf('\n', defaultAt)).trim();
            Assertions.assertEquals("default: op", setting, "group " + group.node());
        }
    }

    @Test
    @DisplayName("a sender granted nothing sends literal text, tags and all")
    void grantedNothingSendsLiteralText() {
        Component rendered = render("<red>hello <bold>there", DENY_ALL);

        Assertions.assertEquals("<red>hello <bold>there", PLAIN.serialize(rendered));
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
        TagResolver allowed = MailFormatting.resolverFor(COSMETIC_ONLY);
        String once = MailFormatting.sanitize("<red>hi <click:run_command:/op me>x</click>", allowed);
        String twice = MailFormatting.sanitize(once, allowed);

        Assertions.assertEquals(once, twice);
    }

    @Test
    @DisplayName("a message of nothing but tags sanitises to blank so the caller can reject it")
    void tagOnlyMessageSanitisesToBlank() {
        String stored = MailFormatting.sanitize("<red>", MailFormatting.resolverFor(COSMETIC_ONLY));

        Assertions.assertTrue(stored.isBlank(), "expected blank, got: " + stored);
    }

    @Test
    @DisplayName("plain text passes through unchanged")
    void plainTextIsUnchanged() {
        String stored = MailFormatting.sanitize("hello there", MailFormatting.resolverFor(COSMETIC_ONLY));

        Assertions.assertEquals("hello there", stored);
        Assertions.assertEquals("hello there",
                PLAIN.serialize(MiniMessage.miniMessage().deserialize(stored)));
    }
}
