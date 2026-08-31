package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The two buttons the DM sink puts under the mail arrival notice: <em>Open message</em>, a shortcut
 * for {@code /mail read 1}, and <em>Mark as read</em>, which stamps the same entry seen without showing
 * it. Both exist so a player told about mail in Discord can deal with it without typing a command —
 * one for the mail they want to read now, one for the interruption they want to clear.
 *
 * <p>Discord-only by construction: it is a JDA component, and the affordance exists because a Discord
 * message can carry a button. The notice itself is medium-neutral and unchanged, and no other sink
 * learns anything from this.
 *
 * <p>The notice is recognised through {@link MailNotifier#isArrivalNotice(RenderableNotification)},
 * which is an <strong>identity</strong> check on the notice's body rather than a comparison of its
 * wording. Matching the text would mean rewording the notice silently dropped the button, and would put
 * one on any notification that happened to render the same way. The body carries the mark because the
 * title names the sender and so differs from send to send.
 */
public final class MailNoticeButton {

    /** What the button opens: the first entry of the first page of the player's mail. */
    private static final String FIRST_PAGE = "1";
    private static final String FIRST_ENTRY = "1";

    private static final String OPEN_LABEL = "Open message";
    private static final String SEEN_LABEL = "Mark as read";

    private MailNoticeButton() {
    }

    /**
     * The row to add under {@code notification}'s message, or empty when it is not the arrival notice —
     * an ordinary notification's DM has no first-mail for the button to open.
     */
    public static @NotNull Optional<ActionRow> forNotification(@NotNull RenderableNotification notification) {
        if (!MailNotifier.isArrivalNotice(notification)) {
            return Optional.empty();
        }
        return Optional.of(ActionRow.of(
                Button.primary(
                        ComponentIds.encode(ComponentIds.SURFACE_INBOX_MAIL, "read", FIRST_PAGE,
                                FIRST_ENTRY),
                        OPEN_LABEL),
                Button.secondary(
                        ComponentIds.encode(ComponentIds.SURFACE_INBOX_MAIL, "seen", FIRST_PAGE,
                                FIRST_ENTRY),
                        SEEN_LABEL)));
    }
}
