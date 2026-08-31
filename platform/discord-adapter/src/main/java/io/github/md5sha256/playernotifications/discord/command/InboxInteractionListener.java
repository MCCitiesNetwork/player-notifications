package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Buttons and row selects on an inbox listing, for both surfaces.
 *
 * <p>One listener for {@code /mail} and {@code /notifications} rather than two, because the surface
 * travels in the component id: the id decides which {@link InboxView} answers, so a click on a mail
 * listing can never be served by the unfiltered view.
 */
public final class InboxInteractionListener extends ListenerAdapter {

    /**
     * The actions posted by the mail arrival notice's buttons. They are answered with a fresh
     * ephemeral reply rather than by editing, because the message carrying them is a DM the player
     * may still want.
     */
    private static final Set<String> NOTICE_ACTIONS = Set.of("read", "seen");

    private final DiscordUserResolver users;
    private final InboxView notifications;
    private final InboxView mail;
    private final InboxMessageFactory messages;
    private final Executor asyncExecutor;
    private final Logger logger;

    public InboxInteractionListener(@NotNull DiscordUserResolver users, @NotNull InboxView notifications,
                                    @NotNull InboxView mail, @NotNull InboxMessageFactory messages,
                                    @NotNull Executor asyncExecutor, @NotNull Logger logger) {
        this.users = users;
        this.notifications = notifications;
        this.mail = mail;
        this.messages = messages;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        Optional<ComponentIds.Parsed> parsed = ours(String.valueOf(event.getComponentId()));
        if (parsed.isEmpty()) {
            return;
        }
        // The arrival notice's buttons sit on an ordinary DM rather than on a listing this module
        // posted, so they answer with a new ephemeral reply: editing would consume the notice, and a
        // player with several unread mails still wants it there.
        Runnable defer = NOTICE_ACTIONS.contains(parsed.get().action())
                ? () -> event.deferReply(true).queue()
                : () -> event.deferEdit().queue();
        handle(event.getHook(), parsed.get(), event.getUser().getIdLong(), null, defer);
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        Optional<ComponentIds.Parsed> parsed = ours(String.valueOf(event.getComponentId()));
        if (parsed.isEmpty()) {
            return;
        }
        String selected = event.getValues().isEmpty() ? null : event.getValues().get(0);
        handle(event.getHook(), parsed.get(), event.getUser().getIdLong(), selected,
                () -> event.deferEdit().queue());
    }

    private @NotNull Optional<ComponentIds.Parsed> ours(@NotNull String componentId) {
        return ComponentIds.parse(componentId)
                .filter(parsed -> parsed.surface().equals(ComponentIds.SURFACE_INBOX)
                        || parsed.surface().equals(ComponentIds.SURFACE_INBOX_MAIL));
    }

    private void handle(@NotNull InteractionHook hook, @NotNull ComponentIds.Parsed parsed,
                        long discordId, String selectedKey, @NotNull Runnable defer) {
        defer.run();
        InteractionSupport.async(this.asyncExecutor, hook, this.logger, () -> {
            Optional<UUID> player = this.users.resolve(discordId);
            if (player.isEmpty()) {
                InteractionSupport.reply(hook, DiscordUserResolver.NOT_LINKED_MESSAGE, this.logger);
                return;
            }
            InboxView view = parsed.surface().equals(ComponentIds.SURFACE_INBOX_MAIL)
                    ? this.mail
                    : this.notifications;

            switch (parsed.action()) {
                case "prev", "next", "page" -> InteractionSupport.edit(hook,
                        this.messages.listing(view.page(player.get(), parsed.intArg(0).orElse(1)),
                                parsed.surface()),
                        this.logger);
                case "open" -> {
                    if (selectedKey == null) {
                        return;
                    }
                    InboxView.ReadResult result = view.readByKey(player.get(), selectedKey);
                    if (result instanceof InboxView.ReadResult.Ok ok) {
                        InteractionSupport.edit(hook, this.messages.detail(ok.row(), parsed.surface()),
                                this.logger);
                    } else {
                        // Dismissed or expired between the listing being posted and the row being clicked.
                        InteractionSupport.edit(hook,
                                this.messages.listing(view.page(player.get(), 1), parsed.surface()),
                                this.logger);
                    }
                }
                case "unread" -> {
                    parsed.arg(0).ifPresent(key -> view.markUnreadByKey(player.get(), key));
                    // Back to the listing, where the row is now shown unread — the only place the
                    // change is visible, since the detail embed says nothing about read state.
                    InteractionSupport.edit(hook,
                            this.messages.listing(view.page(player.get(), 1), parsed.surface()),
                            this.logger);
                }
                case "read" -> {
                    InboxView.ReadResult result = view.read(player.get(),
                            parsed.intArg(0).orElse(1), parsed.intArg(1).orElse(1));
                    if (result instanceof InboxView.ReadResult.Ok ok) {
                        InteractionSupport.reply(hook, this.messages.detail(ok.row(), parsed.surface()),
                                this.logger);
                    } else {
                        InboxView.ReadResult.OutOfRange range = (InboxView.ReadResult.OutOfRange) result;
                        InteractionSupport.reply(hook,
                                InboxReplies.outOfRange(range.entry(), range.rowCount()), this.logger);
                    }
                }
                case "seen" -> InteractionSupport.reply(hook,
                        InboxReplies.of(view.markSeen(player.get(),
                                parsed.intArg(0).orElse(1), parsed.intArg(1).orElse(1))),
                        this.logger);
                case "delete" -> {
                    parsed.arg(0).ifPresent(key -> view.dismissByKey(player.get(), key));
                    InteractionSupport.edit(hook,
                            this.messages.listing(view.page(player.get(), 1), parsed.surface()),
                            this.logger);
                }
                default -> this.logger.fine(() -> "Unknown inbox component action: " + parsed.action());
            }
        });
    }
}
