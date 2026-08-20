package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.discord.DiscordMarkdownSerializer;
import io.github.md5sha256.playernotifications.paper.inbox.InboxEntryRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Reading and acting on one player's inbox from Discord: the Discord-side counterpart of the host's
 * {@code InboxRouter}, holding every decision the listeners over it would otherwise contain.
 *
 * <p>Constructed twice — once filtered to {@code mail} and titled "Mail", once unfiltered and titled
 * "Notifications" — which is exactly how the host runs two routers, and for the same reason: the
 * filter has to reach every call, or a mail command would act on the whole inbox.
 *
 * <p>The page size is a supplier, not a captured int: the host's {@code inbox-page-size} is
 * reloadable, and a size fixed at construction would leave this surface paging differently from every
 * other one until a restart.
 *
 * <p>Holds <b>no state</b>. The page a row belongs to arrives as a slash option or inside a component
 * id, so there is no cursor to expire, and no way for a stale listing to resolve an entry against a
 * page the player is no longer looking at. Every method blocks on JDBC; call them off the main thread.
 */
public final class InboxView {

    private final NotificationService service;
    private final InboxEntryRenderer renderer;
    private final @Nullable String dataTypeFilter;
    private final String title;
    private final IntSupplier pageSize;

    public InboxView(@NotNull NotificationService service, @NotNull InboxEntryRenderer renderer,
                     @Nullable String dataTypeFilter, @NotNull String title,
                     @NotNull IntSupplier pageSize) {
        this.service = service;
        this.renderer = renderer;
        this.dataTypeFilter = dataTypeFilter;
        this.title = title;
        this.pageSize = pageSize;
    }

    /** The data type this view is filtered to, or {@code null} when it shows everything. */
    public @Nullable String dataTypeFilter() {
        return this.dataTypeFilter;
    }

    /** One page, rendered. {@code page} is 1-based; the service clamps it into {@code 1..totalPages}. */
    public @NotNull Page page(@NotNull UUID player, int page) {
        InboxPage stored = this.service.inbox(player, page, this.pageSize.getAsInt(), this.dataTypeFilter);
        List<Row> rows = new ArrayList<>(stored.entries().size());
        int entry = 1;
        for (InboxEntry inboxEntry : stored.entries()) {
            rows.add(row(entry++, inboxEntry, player));
        }
        return new Page(this.title, List.copyOf(rows), stored.page(), stored.totalPages(),
                stored.totalEntries(), stored.unreadCount());
    }

    /** Reads row {@code entry} of {@code page}, marking it seen. */
    public @NotNull ReadResult read(@NotNull UUID player, int page, int entry) {
        Page rendered = page(player, page);
        Optional<Row> row = rowAt(rendered, entry);
        if (row.isEmpty()) {
            return new ReadResult.OutOfRange(entry, rendered.rows().size());
        }
        this.service.markSeen(row.get().notifKey(), player);
        return new ReadResult.Ok(row.get());
    }

    /**
     * Reads the notification with this key, marking it seen — the component path, where the row was
     * chosen by clicking it and its key came back in the component id.
     */
    public @NotNull ReadResult readByKey(@NotNull UUID player, @NotNull String notifKey) {
        Optional<Row> row = findByKey(player, notifKey);
        if (row.isEmpty()) {
            // Dismissed or expired between the listing being posted and the row being clicked.
            return new ReadResult.OutOfRange(0, 0);
        }
        this.service.markSeen(notifKey, player);
        return new ReadResult.Ok(row.get());
    }

    /** Dismisses row {@code entry} of {@code page}. */
    public @NotNull ActionResult dismiss(@NotNull UUID player, int page, int entry) {
        Page rendered = page(player, page);
        Optional<Row> row = rowAt(rendered, entry);
        if (row.isEmpty()) {
            return new ActionResult.OutOfRange(entry, rendered.rows().size());
        }
        return dismissByKey(player, row.get().notifKey());
    }

    /** Dismisses the notification with this key. */
    public @NotNull ActionResult dismissByKey(@NotNull UUID player, @NotNull String notifKey) {
        this.service.deleteNotificationTarget(notifKey, player);
        return new ActionResult.Ok("Dismissed.");
    }

    /**
     * Empties this view: marks everything seen, then dismisses it. Composed from the two service calls
     * rather than a third, the same way the in-game {@code clear} is — and both halves carry the
     * filter, or clearing mail would dismiss the player's notifications too.
     */
    public @NotNull ActionResult clear(@NotNull UUID player) {
        this.service.markAllSeen(player, this.dataTypeFilter);
        this.service.dismissSeen(player, this.dataTypeFilter);
        return new ActionResult.Ok("Cleared.");
    }

    private @NotNull Optional<Row> findByKey(@NotNull UUID player, @NotNull String notifKey) {
        int page = 1;
        int totalPages;
        do {
            Page rendered = page(player, page);
            totalPages = rendered.totalPages();
            Optional<Row> match = rendered.rows().stream()
                    .filter(row -> row.notifKey().equals(notifKey))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
            page++;
        } while (page <= totalPages);
        return Optional.empty();
    }

    private static @NotNull Optional<Row> rowAt(@NotNull Page page, int entry) {
        return entry >= 1 && entry <= page.rows().size()
                ? Optional.of(page.rows().get(entry - 1))
                : Optional.empty();
    }

    private @NotNull Row row(int entry, @NotNull InboxEntry inboxEntry, @NotNull UUID viewer) {
        RenderableNotification rendered = this.renderer.render(inboxEntry, viewer);
        return new Row(entry, inboxEntry.notifKey(), inboxEntry.unread(),
                DiscordMarkdownSerializer.serialize(rendered.title()),
                DiscordMarkdownSerializer.serialize(rendered.body()));
    }

    /** One rendered page. */
    public record Page(@NotNull String title, @NotNull List<Row> rows, int page, int totalPages,
                       int totalEntries, int unreadCount) {

        public boolean isEmpty() {
            return this.rows.isEmpty();
        }
    }

    /** One rendered row. {@code title} and {@code body} are already Discord markdown. */
    public record Row(int entry, @NotNull String notifKey, boolean unread,
                      @NotNull String title, @NotNull String body) {
    }

    /** The outcome of reading one row. */
    public sealed interface ReadResult {

        record Ok(@NotNull Row row) implements ReadResult {
        }

        /**
         * The row asked for is not on the page. Reported rather than clamped: an out-of-range entry
         * means the inbox changed under the player, and acting on the wrong notification is worse than
         * asking them to look again.
         */
        record OutOfRange(int entry, int rowCount) implements ReadResult {
        }
    }

    /** The outcome of dismissing or clearing. */
    public sealed interface ActionResult {

        record Ok(@NotNull String message) implements ActionResult {
        }

        record OutOfRange(int entry, int rowCount) implements ActionResult {
        }
    }
}
