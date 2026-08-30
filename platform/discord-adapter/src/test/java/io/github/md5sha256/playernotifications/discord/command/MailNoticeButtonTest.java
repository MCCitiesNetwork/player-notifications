package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The one-button row the Discord DM sink adds under the mail arrival notice.
 *
 * <p>Identified through the host's own {@code MailNotifier.isArrivalNotice} rather than by matching the
 * notice's wording, so rewording the notice cannot silently drop the button.
 */
class MailNoticeButtonTest {

    /**
     * A real notice from a real notifier: the button's contract is with what {@code MailNotifier}
     * builds, not with a hand-assembled record. The container is empty rather than the host's shipped
     * {@code messages.yml} — that lives in the host's test source set, out of this module's reach, and
     * nothing here asserts the notice's wording. Only its body, the marker, matters to the button.
     */
    private static RenderableNotification arrivalNotice() {
        return new MailNotifier(new NotificationSinkRegistry(), player -> Set.of(),
                new MessageContainer(),
                Logger.getLogger(MailNoticeButtonTest.class.getName())).arrivalNotice("Andrew");
    }

    private static Button button(ActionRow row) {
        return (Button) row.getComponents().get(0);
    }

    @Test
    void theArrivalNoticeGetsAReadMailButton() {
        Optional<ActionRow> row = MailNoticeButton.forNotification(arrivalNotice());

        Assertions.assertTrue(row.isPresent());
        Assertions.assertEquals(1, row.get().getComponents().size(), "one button, not a row of them");
        Assertions.assertEquals("Read mail", button(row.get()).getLabel());
    }

    @Test
    void theButtonOpensEntryOneOfTheFirstMailPage() {
        // The same thing /mail read 1 does, which is what the button is a shortcut for.
        ActionRow row = MailNoticeButton.forNotification(arrivalNotice()).orElseThrow();

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
                Component.text("You were sent mail from Andrew"), Component.text("Use /mail to read it."));

        Assertions.assertEquals(Optional.empty(), MailNoticeButton.forNotification(other));
    }
}
