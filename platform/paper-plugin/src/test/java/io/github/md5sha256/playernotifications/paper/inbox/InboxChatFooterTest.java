package io.github.md5sha256.playernotifications.paper.inbox;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.paper.localisation.TestMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat listing's pager.
 *
 * <p>Every case runs against the <strong>shipped</strong> {@code messages.yml}, so a wording change
 * that drops {@code <page>} or an arrow fails here rather than in game.
 */
class InboxChatFooterTest {

    private static final MessageContainer MESSAGES = TestMessages.shipped();

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Every {@code runCommand} target in the tree, in encounter order. The arrows arrive as
     * placeholder-inserted children, so the click is never on the root.
     */
    private static List<String> commands(Component component) {
        List<String> found = new ArrayList<>();
        collect(component, found);
        return found;
    }

    private static void collect(Component component, List<String> into) {
        ClickEvent click = component.clickEvent();
        // payload() rather than the deprecated value(): a RUN_COMMAND always carries a Text payload.
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND
                && click.payload() instanceof ClickEvent.Payload.Text text) {
            into.add(text.value());
        }
        for (Component child : component.children()) {
            collect(child, into);
        }
    }

    @Test
    void singlePageHasNoFooter() {
        assertNull(InboxChatFooter.build(MESSAGES, "notifications", 1, 1),
                "a pager offering no destination is noise, so one page gets none");
    }

    @Test
    void middlePageLinksBothWays() {
        Component footer = InboxChatFooter.build(MESSAGES, "notifications", 2, 3);

        assertNotNull(footer);
        String text = plain(footer);
        assertTrue(text.contains("«"), text);
        assertTrue(text.contains("»"), text);
        assertTrue(text.contains("Page 2 of 3"), text);
        assertEquals(List.of("/notifications list 1", "/notifications list 3"), commands(footer));
    }

    @Test
    void firstPagePreviousIsInert() {
        Component footer = InboxChatFooter.build(MESSAGES, "notifications", 1, 3);

        assertNotNull(footer);
        // Still rendered — hiding it would shift the footer's width from page to page.
        assertTrue(plain(footer).contains("«"), plain(footer));
        assertEquals(List.of("/notifications list 2"), commands(footer));
    }

    @Test
    void lastPageNextIsInert() {
        Component footer = InboxChatFooter.build(MESSAGES, "notifications", 3, 3);

        assertNotNull(footer);
        assertTrue(plain(footer).contains("»"), plain(footer));
        assertEquals(List.of("/notifications list 2"), commands(footer));
    }

    @Test
    void commandLabelIsHonoured() {
        Component footer = InboxChatFooter.build(MESSAGES, "mail", 2, 3);

        assertNotNull(footer);
        // The two routers share this class, so a mail listing must never page the other inbox.
        assertEquals(List.of("/mail list 1", "/mail list 3"), commands(footer));
    }
}
