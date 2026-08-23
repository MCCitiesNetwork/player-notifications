package io.github.md5sha256.playernotifications.paper.inbox;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.paper.ui.DialogSupport;
import io.github.md5sha256.playernotifications.paper.ui.PageBounds;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owns the two inbox screens, the per-player page cursor, and the async marshalling between them —
 * the same shape as {@code PreferenceDialogRouter}, and for the same reason: {@link NotificationService}
 * does blocking JDBC while {@code Player#showDialog} must run on the main thread.
 *
 * <p>The cursor also backs the chat fallback: {@code /notifications read <entry>} and
 * {@code … dismiss <entry>} index the page most recently listed for that player.
 */
public final class InboxRouter {

    private final MessageContainer messages;
    private final Plugin plugin;
    private final NotificationService service;
    private final InboxEntryRenderer renderer;
    private final InboxDialog listDialog;
    private final InboxDetailDialog detailDialog;
    private final InboxFilterDialog filterDialog;
    /** The screen's own name, so the chat fallback's header matches the dialog's title. */
    private final Component title;

    /** How this screen words one row of its chat listing. See {@link InboxChatRow}. */
    private final InboxChatRow chatRow;

    /**
     * The data types this screen is permanently restricted to, or {@code null} for a screen the player
     * may filter themselves. Threaded into every {@link NotificationService} call this router makes, so
     * {@code /mail}'s paging and counts agree with its own filtered view rather than the whole inbox.
     *
     * <p>A pinned router ({@code /mail}) admits no player-chosen filter and shows no Filter button.
     */
    private final Set<String> pinnedFilter;

    /**
     * The command this screen belongs to ({@code notifications} or {@code mail}), used to word the chat
     * fallback's hint. Hardcoding {@code /notifications} there told a {@code /mail list} reader to run a
     * command that resolves against the other router's page.
     */
    private final String commandLabel;

    /**
     * What each player last listed, held separately for the dialog and for chat so that neither surface
     * can move the other's index. Dropped on quit by {@code InboxQuitListener}. See {@link InboxCursors}.
     */
    private final InboxCursors cursors = new InboxCursors();

    /**
     * The category each player has filtered the <b>dialog</b> to, absent meaning unfiltered. Deliberately
     * not on {@link InboxCursors}: the commands read that state, and a filter must never reach them.
     * Dropped on quit with everything else.
     */
    private final Map<UUID, String> dialogCategory = new ConcurrentHashMap<>();

    /** Resolves a picked category to its data types. {@code null} on a pinned router, which cannot filter. */
    private final InboxFilters filters;

    private volatile int pageSize;

    public InboxRouter(@NotNull MessageContainer messages,
                       @NotNull Plugin plugin,
                       @NotNull NotificationService service,
                       @NotNull InboxEntryRenderer renderer,
                       int pageSize,
                       @Nullable Set<String> pinnedFilter,
                       @Nullable InboxFilters filters,
                       @NotNull String commandLabel,
                       @NotNull Component title,
                       @NotNull InboxChatRow chatRow) {
        this.messages = messages;
        this.plugin = plugin;
        this.service = service;
        this.renderer = renderer;
        this.pageSize = pageSize;
        this.pinnedFilter = pinnedFilter;
        this.filters = filters;
        this.commandLabel = commandLabel;
        this.title = title;
        this.chatRow = chatRow;
        this.listDialog = new InboxDialog(this, title);
        this.detailDialog = new InboxDetailDialog(this);
        this.filterDialog = new InboxFilterDialog(this);
    }

    /** Applies a reloaded {@code inbox-page-size}, the same volatile-field idiom the other reloads use. */
    public void reloadPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public @NotNull NotificationService service() {
        return this.service;
    }

    public @NotNull InboxEntryRenderer renderer() {
        return this.renderer;
    }

    public @NotNull Plugin plugin() {
        return this.plugin;
    }

    public void drop(@NotNull UUID playerId) {
        this.cursors.drop(playerId);
        this.dialogCategory.remove(playerId);
    }

    /**
     * Whether this screen offers a filter at all. A pinned router ({@code /mail}) does not: its scope is
     * the whole point of it being a separate screen.
     */
    boolean filterable() {
        return this.pinnedFilter == null && this.filters != null;
    }

    /**
     * The data types the <b>dialog</b> currently shows for this player: their picked category if any,
     * otherwise this screen's pinned scope. Never consulted by a command — see {@link #dialogCategory}.
     */
    private @Nullable Set<String> activeFilter(@NotNull UUID playerId) {
        if (!filterable()) {
            return this.pinnedFilter;
        }
        String category = this.dialogCategory.get(playerId);
        return category == null ? null : this.filters.resolve(category);
    }

    /** The label for the dialog's Filter button: the picked category's name, or "All notifications". */
    @NotNull Component filterLabel(@NotNull UUID playerId) {
        return this.filters == null
                ? Component.empty()
                : this.filters.label(this.dialogCategory.get(playerId));
    }

    /**
     * Opens the filter picker, reading each category's counts off the main thread.
     *
     * <p>One count pair per category. Categories are few and this read is already async; if a server ever
     * defines enough of them for it to hurt, the fix is one grouped count folded together in Java rather
     * than a different screen.
     */
    public void openFilterPicker(@NotNull Player player) {
        if (!filterable()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            UUID id = player.getUniqueId();
            List<InboxFilterDialog.Row> rows = new ArrayList<>();
            rows.add(row(id, null));
            for (String key : this.filters.categoryKeys()) {
                rows.add(row(id, key));
            }
            String active = this.dialogCategory.get(id);
            DialogSupport.onMainThread(this.plugin, player,
                    () -> this.filterDialog.show(player, rows, active));
        });
    }

    private InboxFilterDialog.Row row(@NotNull UUID playerId, @Nullable String categoryKey) {
        Set<String> filter = categoryKey == null ? null : this.filters.resolve(categoryKey);
        int total = this.service.inbox(playerId, 1, 1, filter).totalEntries();
        int unread = this.service.unreadCount(playerId, filter);
        return new InboxFilterDialog.Row(categoryKey, this.filters.label(categoryKey), unread, total);
    }

    /**
     * Applies a picked category and reopens the list at page 1. A {@code null} key clears the filter.
     *
     * <p>Page 1 rather than the page they were on: the old page number means nothing against a different
     * result set, and landing on "page 3 of 1" clamped back would look like the filter had failed.
     */
    public void applyDialogFilter(@NotNull Player player, @Nullable String categoryKey) {
        UUID id = player.getUniqueId();
        if (categoryKey == null) {
            this.dialogCategory.remove(id);
        } else {
            this.dialogCategory.put(id, categoryKey);
        }
        openInbox(player, 1);
    }

    /**
     * Opens the list screen at the given page, reading off the main thread and showing back on it.
     *
     * <p>An empty inbox replies in chat instead of opening a dialog. A list screen with nothing on it
     * has no rows and no bulk buttons, leaving {@code multi_action} with an empty {@code actions} list
     * — which the client-bound codec rejects as non-empty, so the dialog never rendered at all. A
     * one-line reply is also simply the better answer to "show me nothing".
     */
    public void openInbox(@NotNull Player player, int page) {
        withPage(player, page, activeFilter(player.getUniqueId()), Surface.DIALOG, read -> {
            if (read.entries().isEmpty()) {
                player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_EMPTY));
                // An empty *filtered* screen opens the picker instead of leaving the player in chat.
                // Without this the filter is a trap: the list screen is the only route to the Filter
                // button, an empty one never opens, and reopening /notifications re-reads the same
                // filter — so the player could not clear their own choice short of quitting. The
                // picker cannot itself be empty, so this cannot bounce.
                if (this.dialogCategory.containsKey(player.getUniqueId())) {
                    openFilterPicker(player);
                }
                return;
            }
            DialogSupport.onMainThread(this.plugin, player, () -> this.listDialog.show(player, read));
        });
    }

    /** Opens one entry's detail screen, marking it seen. */
    public void openEntry(@NotNull Player player, @NotNull String notificationKey) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            UUID id = player.getUniqueId();
            this.service.markSeen(notificationKey, id);
            InboxEntry entry = findEntry(id, notificationKey);
            if (entry == null) {
                DialogSupport.message(this.plugin, player,
                        this.messages.messageFor(MessageKeys.INBOX_GONE));
                return;
            }
            var rendered = this.renderer.render(entry, id);
            DialogSupport.onMainThread(this.plugin, player,
                    () -> this.detailDialog.show(player, entry, rendered));
        });
    }

    public void dismissEntry(@NotNull Player player, @NotNull String notificationKey) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            this.service.deleteNotificationTarget(notificationKey, player.getUniqueId());
            openInbox(player, currentPage(player.getUniqueId()));
        });
    }

    public void markAllSeen(@NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            this.service.markAllSeen(player.getUniqueId(), activeFilter(player.getUniqueId()));
            openInbox(player, currentPage(player.getUniqueId()));
        });
    }

    public void dismissSeen(@NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            this.service.dismissSeen(player.getUniqueId(), activeFilter(player.getUniqueId()));
            openInbox(player, 1);
        });
    }

    /**
     * {@code /notifications clear}: empties the inbox outright, unread entries included — the shorthand
     * for what the list screen otherwise needs "Mark all read" then "Delete all read" to do. It is
     * composed from exactly those two service calls rather than a new one, so it dismisses by deleting
     * target rows like every other dismissal and needs no extra query.
     *
     * <p>The chat index is dropped afterwards, so a stale {@code read <entry>}/{@code delete <entry>}
     * cannot resolve against entries that no longer exist. Only the chat index: the dialog resolves a
     * clicked row by that row's own key, and an open dialog is not this command's business.
     */
    public void clearInChat(@NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            UUID id = player.getUniqueId();
            int total = this.service.inbox(id, 1, 1, this.pinnedFilter).totalEntries();
            if (total == 0) {
                player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_ALREADY_EMPTY));
                return;
            }
            this.service.markAllSeen(id, this.pinnedFilter);
            this.service.dismissSeen(id, this.pinnedFilter);
            this.cursors.clearChat(id);
            // Separate keys rather than an inline ternary: pluralisation is a wording decision, and
            // an operator translating this needs both forms in the file to change.
            player.sendMessage(total == 1
                    ? this.messages.messageFor(MessageKeys.INBOX_CLEARED_ONE)
                    : this.messages.messageFor(MessageKeys.INBOX_CLEARED_MANY,
                            MessageContainer.value("count", String.valueOf(total))));
        });
    }

    /** The chat fallback's listing: {@code /notifications list [page]}. */
    public void listInChat(@NotNull Player player, int page) {
        withPage(player, page, this.pinnedFilter, Surface.CHAT, read -> {
            if (read.entries().isEmpty()) {
                player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_EMPTY));
                return;
            }
            player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_HEADER,
                    MessageContainer.markup("title", this.title),
                    MessageContainer.value("page", String.valueOf(read.page())),
                    MessageContainer.value("total-pages", String.valueOf(read.totalPages())),
                    MessageContainer.value("unread", String.valueOf(read.unreadCount()))));
            int index = 1;
            for (InboxEntry entry : read.entries()) {
                var rendered = this.renderer.render(entry, player.getUniqueId());
                String readCommand = "/" + this.commandLabel + " read " + index;
                player.sendMessage(this.chatRow.format(index, entry, rendered, entry.unread())
                        // The whole row is the button: hovering names the command and clicking runs it,
                        // so a chat-fallback reader never has to retype an index they can already see.
                        // The click target stays in Java: a <click> tag argument is a position no
                        // TagResolver can fill, and moving it into the file would need a subclass.
                        .hoverEvent(HoverEvent.showText(
                                this.messages.messageFor(MessageKeys.INBOX_ROW_HOVER,
                                        MessageContainer.value("command", readCommand))))
                        .clickEvent(ClickEvent.runCommand(readCommand)));
                index++;
            }
            player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_USAGE,
                    MessageContainer.value("command", this.commandLabel)));
        });
    }

    /** {@code /notifications read <entry>}: shows entry n of the last listed page in chat, marking it seen. */
    public void readInChat(@NotNull Player player, int index) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            InboxEntry entry = indexed(player, index);
            if (entry == null) {
                return;
            }
            this.service.markSeen(entry.notifKey(), player.getUniqueId());
            var rendered = this.renderer.render(entry, player.getUniqueId());
            player.sendMessage(rendered.title().colorIfAbsent(NamedTextColor.GOLD));
            player.sendMessage(rendered.body());
        });
    }

    /** {@code /notifications delete <entry>}: removes entry n of the last listed page. */
    public void dismissInChat(@NotNull Player player, int index) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            InboxEntry entry = indexed(player, index);
            if (entry == null) {
                return;
            }
            this.service.deleteNotificationTarget(entry.notifKey(), player.getUniqueId());
            player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_DELETED,
                    MessageContainer.markup("title",
                            this.renderer.render(entry, player.getUniqueId()).title())));
        });
    }

    /**
     * Resolves an entry by its 1-based position on the last listed page, replying and returning
     * {@code null} when nothing has been listed or the index is out of range.
     */
    private InboxEntry indexed(@NotNull Player player, int index) {
        List<InboxEntry> listed = this.cursors.chatListed(player.getUniqueId());
        if (listed == null || listed.isEmpty()) {
            player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_LIST_FIRST,
                    MessageContainer.value("command", this.commandLabel)));
            return null;
        }
        if (index < 1 || index > listed.size()) {
            player.sendMessage(this.messages.messageFor(MessageKeys.INBOX_NO_ENTRY,
                    MessageContainer.value("entry", String.valueOf(index))));
            return null;
        }
        return listed.get(index - 1);
    }

    private int currentPage(@NotNull UUID playerId) {
        return this.cursors.dialogPage(playerId);
    }

    private InboxEntry findEntry(@NotNull UUID playerId, @NotNull String notificationKey) {
        InboxEntry entry = this.cursors.dialogEntry(playerId, notificationKey);
        // Re-read so seenTime reflects the markSeen just performed.
        return entry == null ? null : withSeen(entry);
    }

    private static InboxEntry withSeen(@NotNull InboxEntry entry) {
        return entry.unread()
                ? new InboxEntry(entry.notifKey(), entry.notifScheduledTime(), entry.notifExpiryTime(),
                entry.notifPayloadType(), entry.notifPayload(), entry.notifPriority(), java.time.Instant.now())
                : entry;
    }

    /**
     * Reads one page off the main thread and hands it to {@code consumer} on that same async thread,
     * recording the cursor and the listed entries so the chat fallback and the paging buttons can
     * resolve against them.
     */
    private void withPage(@NotNull Player player, int page, @Nullable Set<String> filter,
                          @NotNull Surface surface, @NotNull Consumer<InboxPage> consumer) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            UUID id = player.getUniqueId();
            InboxPage read = this.service.inbox(id, page, this.pageSize, filter);
            // Clamped twice on the way in, here and in the service. One is a UI helper and the other a
            // public-API trust boundary; neither should assume the other ran.
            if (surface == Surface.DIALOG) {
                this.cursors.recordDialog(id,
                        new PageBounds(read.page(), read.pageSize(), read.totalEntries()), read.entries());
            } else {
                this.cursors.recordChat(id, read.entries());
            }
            consumer.accept(read);
        });
    }

    /** Which screen a read is for, deciding which of the two indexes it records into. */
    private enum Surface { DIALOG, CHAT }
}
