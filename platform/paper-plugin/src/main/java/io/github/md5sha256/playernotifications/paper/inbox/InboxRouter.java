package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.paper.ui.DialogSupport;
import io.github.md5sha256.playernotifications.paper.ui.PageBounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
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

    /** Shared by the dialog entry point and the chat fallback, so the two cannot word it differently. */
    private static final Component EMPTY_MESSAGE =
            Component.text("Your inbox is empty.", NamedTextColor.GRAY);

    private final Plugin plugin;
    private final NotificationService service;
    private final InboxEntryRenderer renderer;
    private final InboxDialog listDialog;
    private final InboxDetailDialog detailDialog;
    /** The screen's own name, so the chat fallback's header matches the dialog's title. */
    private final Component title;

    /**
     * The data type this screen is restricted to, or {@code null} for unfiltered. Threaded into every
     * {@link NotificationService} call this router makes, so {@code /mail}'s paging and counts agree with
     * its own filtered view rather than the whole inbox.
     */
    private final String dataTypeFilter;

    /**
     * The command this screen belongs to ({@code notifications} or {@code mail}), used to word the chat
     * fallback's hint. Hardcoding {@code /notifications} there told a {@code /mail list} reader to run a
     * command that resolves against the other router's page.
     */
    private final String commandLabel;

    /** The last page each player looked at. Dropped on quit by {@code InboxQuitListener}. */
    private final Map<UUID, PageBounds> cursors = new ConcurrentHashMap<>();
    /** The entries of that page, so a chat {@code read <entry>}/{@code dismiss <entry>} can resolve the index. */
    private final Map<UUID, List<InboxEntry>> lastListed = new ConcurrentHashMap<>();

    private volatile int pageSize;

    public InboxRouter(@NotNull Plugin plugin,
                       @NotNull NotificationService service,
                       @NotNull InboxEntryRenderer renderer,
                       int pageSize,
                       @Nullable String dataTypeFilter,
                       @NotNull String commandLabel,
                       @NotNull Component title) {
        this.plugin = plugin;
        this.service = service;
        this.renderer = renderer;
        this.pageSize = pageSize;
        this.dataTypeFilter = dataTypeFilter;
        this.commandLabel = commandLabel;
        this.title = title;
        this.listDialog = new InboxDialog(this, title);
        this.detailDialog = new InboxDetailDialog(this);
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
        this.cursors.remove(playerId);
        this.lastListed.remove(playerId);
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
        withPage(player, page, read -> {
            if (read.entries().isEmpty()) {
                player.sendMessage(EMPTY_MESSAGE);
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
                        Component.text("That notification is no longer in your inbox.", NamedTextColor.RED));
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
            this.service.markAllSeen(player.getUniqueId(), this.dataTypeFilter);
            openInbox(player, currentPage(player.getUniqueId()));
        });
    }

    public void dismissSeen(@NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            this.service.dismissSeen(player.getUniqueId(), this.dataTypeFilter);
            openInbox(player, 1);
        });
    }

    /**
     * {@code /notifications clear}: empties the inbox outright, unread entries included — the shorthand
     * for what the list screen otherwise needs "Mark all read" then "Delete all read" to do. It is
     * composed from exactly those two service calls rather than a new one, so it dismisses by deleting
     * target rows like every other dismissal and needs no extra query.
     *
     * <p>The cursor is dropped afterwards, so a stale {@code read <entry>}/{@code dismiss <entry>} cannot resolve
     * against entries that no longer exist.
     */
    public void clearInChat(@NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            UUID id = player.getUniqueId();
            int total = this.service.inbox(id, 1, 1, this.dataTypeFilter).totalEntries();
            if (total == 0) {
                player.sendMessage(Component.text("Your inbox is already empty.", NamedTextColor.GRAY));
                return;
            }
            this.service.markAllSeen(id, this.dataTypeFilter);
            this.service.dismissSeen(id, this.dataTypeFilter);
            drop(id);
            player.sendMessage(Component.text(
                    total == 1 ? "Cleared 1 notification." : "Cleared " + total + " notifications.",
                    NamedTextColor.GREEN));
        });
    }

    /** The chat fallback's listing: {@code /notifications list [page]}. */
    public void listInChat(@NotNull Player player, int page) {
        withPage(player, page, read -> {
            if (read.entries().isEmpty()) {
                player.sendMessage(EMPTY_MESSAGE);
                return;
            }
            player.sendMessage(this.title.colorIfAbsent(NamedTextColor.GOLD)
                    .append(Component.text(" — page " + read.page() + " of " + read.totalPages()
                            + " (" + read.unreadCount() + " unread)", NamedTextColor.GOLD)));
            int index = 1;
            for (InboxEntry entry : read.entries()) {
                var rendered = this.renderer.render(entry, player.getUniqueId());
                String readCommand = "/" + this.commandLabel + " read " + index;
                NamedTextColor color = entry.unread() ? NamedTextColor.WHITE : NamedTextColor.GRAY;
                player.sendMessage(Component.text(index + ". ", color)
                        .append(rendered.title().colorIfAbsent(color))
                        // The whole row is the button: hovering names the command and clicking runs it,
                        // so a chat-fallback reader never has to retype an index they can already see.
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Click to run " + readCommand, NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.runCommand(readCommand)));
                index++;
            }
            player.sendMessage(Component.text("Use /" + this.commandLabel + " read <entry> or /"
                    + this.commandLabel + " delete <entry>.", NamedTextColor.GRAY));
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
            player.sendMessage(Component.text("Deleted: "
                    + PlainTextComponentSerializer.plainText().serialize(
                            this.renderer.render(entry, player.getUniqueId()).title()),
                    NamedTextColor.GREEN));
        });
    }

    /**
     * Resolves an entry by its 1-based position on the last listed page, replying and returning
     * {@code null} when nothing has been listed or the index is out of range.
     */
    private InboxEntry indexed(@NotNull Player player, int index) {
        List<InboxEntry> listed = this.lastListed.get(player.getUniqueId());
        if (listed == null || listed.isEmpty()) {
            player.sendMessage(Component.text(
                    "Run /" + this.commandLabel + " list first.", NamedTextColor.RED));
            return null;
        }
        if (index < 1 || index > listed.size()) {
            player.sendMessage(Component.text(
                    "No entry " + index + " on that page.", NamedTextColor.RED));
            return null;
        }
        return listed.get(index - 1);
    }

    private int currentPage(@NotNull UUID playerId) {
        PageBounds bounds = this.cursors.get(playerId);
        return bounds == null ? 1 : bounds.page();
    }

    private InboxEntry findEntry(@NotNull UUID playerId, @NotNull String notificationKey) {
        List<InboxEntry> listed = this.lastListed.get(playerId);
        if (listed != null) {
            for (InboxEntry entry : listed) {
                if (entry.notifKey().equals(notificationKey)) {
                    // Re-read so seenTime reflects the markSeen just performed.
                    return withSeen(entry);
                }
            }
        }
        return null;
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
    private void withPage(@NotNull Player player, int page, @NotNull Consumer<InboxPage> consumer) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            UUID id = player.getUniqueId();
            InboxPage read = this.service.inbox(id, page, this.pageSize, this.dataTypeFilter);
            // Clamped twice on the way in, here and in the service. One is a UI helper and the other a
            // public-API trust boundary; neither should assume the other ran.
            this.cursors.put(id, new PageBounds(read.page(), read.pageSize(), read.totalEntries()));
            this.lastListed.put(id, read.entries());
            consumer.accept(read);
        });
    }
}
