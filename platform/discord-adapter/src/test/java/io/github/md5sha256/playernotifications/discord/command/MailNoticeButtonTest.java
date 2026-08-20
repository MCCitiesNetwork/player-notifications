package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * The one-button row the Discord DM sink adds under the mail arrival notice.
 *
 * <p>Identified by equality against the host's own published constant rather than by matching the
 * notice's wording, so rewording the notice cannot silently drop the button.
 */
class MailNoticeButtonTest {

    private static Button button(ActionRow row) {
        return (Button) row.getComponents().get(0);
    }

    @Test
    void theArrivalNoticeGetsAReadMailButton() {
        Optional<ActionRow> row = MailNoticeButton.forNotification(MailNotifier.ARRIVAL_NOTICE);

        Assertions.assertTrue(row.isPresent());
        Assertions.assertEquals(1, row.get().getComponents().size(), "one button, not a row of them");
        Assertions.assertEquals("Read mail", button(row.get()).getLabel());
    }

    @Test
    void theButtonOpensEntryOneOfTheFirstMailPage() {
        // The same thing /mail read 1 does, which is what the button is a shortcut for.
        ActionRow row = MailNoticeButton.forNotification(MailNotifier.ARRIVAL_NOTICE).orElseThrow();

        ComponentIds.Parsed id = ComponentIds.parse(button(row).getCustomId()).orElseThrow();
        Assertions.assertEquals(ComponentIds.SURFACE_INBOX_MAIL, id.surface());
        Assertions.assertEquals("read", id.action());
        Assertions.assertEquals(1, id.intArg(0).orElseThrow(), "page");
        Assertions.assertEquals(1, id.intArg(1).orElseThrow(), "entry");
    }

    @Test
    void anyOtherNotificationGetsNoButton() {
        // An ordinary notification's DM has nothing for the button to open, and a mail-shaped one that
        // did not come from MailNotifier is not the notice.
        RenderableNotification other = new RenderableNotification(
                Component.text("You have new mail!"), Component.text("Use /mail to read it."));

        Assertions.assertEquals(Optional.empty(), MailNoticeButton.forNotification(other));
    }
}
