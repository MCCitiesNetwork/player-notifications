package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import io.github.md5sha256.playernotifications.paper.inbox.InboxEntryRenderer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

class InboxViewTest {

    private static final Logger LOGGER = Logger.getLogger(InboxViewTest.class.getName());
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private NotificationDataTypeRegistry registry;
    private FakeNotificationService service;

    /** A payload whose renderer is registered; stored as the message text, undecorated. */
    private record Note(String text) {
    }

    @BeforeEach
    void setUp() {
        this.registry = new NotificationDataTypeRegistry();
        this.registry.registerPayloadMapping("note", Note.class);
        this.registry.registerSerializer(Note.class, new PayloadSerializer<>() {
            @Override
            public String serialize(Note payload) {
                return payload.text();
            }

            @Override
            public Note deserialize(String payload) {
                return new Note(payload);
            }
        });
        this.registry.registerRenderer(Note.class, (payload, viewer) ->
                new RenderableNotification(Component.text("Note"), Component.text(payload.text())));

        this.registry.registerPayloadMapping(MailPayload.DATA_TYPE, MailPayload.class);
        this.registry.registerSerializer(MailPayload.class, new PayloadSerializer<>() {
            @Override
            public String serialize(MailPayload payload) {
                return payload.message();
            }

            @Override
            public MailPayload deserialize(String payload) {
                return new MailPayload(SENDER, "Steve", payload);
            }
        });
        this.registry.registerRenderer(MailPayload.class, (payload, viewer) ->
                new RenderableNotification(Component.text("Mail from " + payload.senderName()),
                        Component.text(payload.message())));

        this.service = new FakeNotificationService(this.registry);
    }

    private InboxView view(String filter, String title, int pageSize) {
        return view(filter, title, () -> pageSize);
    }

    private InboxView view(String filter, String title, IntSupplier pageSize) {
        return new InboxView(this.service, new InboxEntryRenderer(this.registry, LOGGER),
                filter, title, pageSize);
    }

    private void addNote(String key, String text, boolean unread) {
        this.service.add(new InboxEntry(key, Instant.EPOCH, null, "note", text, 0,
                unread ? null : Instant.EPOCH));
    }

    private void addMail(String key, String message, boolean unread) {
        this.service.add(new InboxEntry(key, Instant.EPOCH, null, MailPayload.DATA_TYPE, message, 0,
                unread ? null : Instant.EPOCH));
    }

    @Test
    void everyRowIsRenderedAndNumberedFromOneWithinThePage() {
        addNote("a", "first", true);
        addNote("b", "second", false);
        addNote("c", "third", true);

        InboxView.Page page = view(null, "Notifications", 10).page(PLAYER, 1);

        Assertions.assertEquals("Notifications", page.title());
        Assertions.assertEquals(List.of(1, 2, 3), page.rows().stream().map(InboxView.Row::entry).toList());
        Assertions.assertEquals("first", page.rows().get(0).body());
        Assertions.assertEquals("Note", page.rows().get(0).title());
        Assertions.assertEquals(List.of(true, false, true),
                page.rows().stream().map(InboxView.Row::unread).toList());
        Assertions.assertEquals(2, page.unreadCount());
        Assertions.assertEquals(3, page.totalEntries());
        Assertions.assertFalse(page.isEmpty());
    }

    @Test
    void aPageBeyondTheLastIsClampedRatherThanErroring() {
        addNote("a", "first", true);
        addNote("b", "second", true);
        addNote("c", "third", true);

        InboxView.Page page = view(null, "Notifications", 2).page(PLAYER, 9);

        Assertions.assertEquals(2, page.page());
        Assertions.assertEquals(2, page.totalPages());
        Assertions.assertEquals(1, page.rows().size(), "the last page holds the remainder");
        Assertions.assertEquals(1, page.rows().get(0).entry(),
                "entries are numbered within the page, not across the inbox");
    }

    @Test
    void thePageSizeIsReadPerCallSoAReloadReachesAnAlreadyBuiltView() {
        // The host's inbox-page-size is reloadable; a size captured at construction would leave the
        // Discord surface paging differently from every other one until a restart.
        addNote("a", "first", true);
        addNote("b", "second", true);
        int[] size = {1};

        InboxView view = view(null, "Notifications", () -> size[0]);
        Assertions.assertEquals(2, view.page(PLAYER, 1).totalPages());

        size[0] = 10;
        Assertions.assertEquals(1, view.page(PLAYER, 1).totalPages());
    }

    @Test
    void aFilteredViewShowsOnlyItsOwnDataType() {
        addNote("a", "not mail", true);
        addMail("m", "hello", true);

        InboxView.Page page = view(MailPayload.DATA_TYPE, "Mail", 10).page(PLAYER, 1);

        Assertions.assertEquals(1, page.rows().size());
        Assertions.assertEquals("Mail from Steve", page.rows().get(0).title());
    }

    @Test
    void readingAnEntryMarksItSeen() {
        addNote("a", "first", true);
        addNote("b", "second", true);

        InboxView.ReadResult result = view(null, "Notifications", 10).read(PLAYER, 1, 2);

        InboxView.Row row = Assertions.assertInstanceOf(InboxView.ReadResult.Ok.class, result).row();
        Assertions.assertEquals("second", row.body());
        Assertions.assertEquals(List.of(new FakeNotificationService.Call("markSeen", null, "b")),
                this.service.calls());
    }

    @Test
    void readingAnEntryThatIsNotOnThePageChangesNothing() {
        // Out of range means the page changed under the player; acting on the wrong row would be worse
        // than asking them to look again, so this is rejected rather than clamped.
        addNote("a", "first", true);

        InboxView.ReadResult result = view(null, "Notifications", 10).read(PLAYER, 1, 9);

        InboxView.ReadResult.OutOfRange outOfRange =
                Assertions.assertInstanceOf(InboxView.ReadResult.OutOfRange.class, result);
        Assertions.assertEquals(9, outOfRange.entry());
        Assertions.assertEquals(1, outOfRange.rowCount());
        Assertions.assertEquals(List.of(), this.service.calls());
    }

    @Test
    void readingByKeyServesTheComponentPathWithoutAPageNumber() {
        addNote("a", "first", true);

        InboxView.ReadResult result = view(null, "Notifications", 10).readByKey(PLAYER, "a");

        Assertions.assertEquals("first",
                Assertions.assertInstanceOf(InboxView.ReadResult.Ok.class, result).row().body());
        Assertions.assertEquals(List.of(new FakeNotificationService.Call("markSeen", null, "a")),
                this.service.calls());
    }

    @Test
    void readingAnUnknownKeyIsOutOfRangeRatherThanAnEmptyDetail() {
        addNote("a", "first", true);

        Assertions.assertInstanceOf(InboxView.ReadResult.OutOfRange.class,
                view(null, "Notifications", 10).readByKey(PLAYER, "gone"));
    }

    @Test
    void dismissingAnEntryDeletesItsTargetRow() {
        addNote("a", "first", true);

        InboxView.ActionResult result = view(null, "Notifications", 10).dismiss(PLAYER, 1, 1);

        Assertions.assertInstanceOf(InboxView.ActionResult.Ok.class, result);
        Assertions.assertEquals(
                List.of(new FakeNotificationService.Call("deleteNotificationTarget", null, "a")),
                this.service.calls());
        Assertions.assertTrue(view(null, "Notifications", 10).page(PLAYER, 1).isEmpty());
    }

    @Test
    void dismissingAnEntryThatIsNotOnThePageChangesNothing() {
        addNote("a", "first", true);

        Assertions.assertInstanceOf(InboxView.ActionResult.OutOfRange.class,
                view(null, "Notifications", 10).dismiss(PLAYER, 1, 4));
        Assertions.assertEquals(List.of(), this.service.calls());
    }

    @Test
    void clearingMarksEverythingSeenAndThenDismissesIt() {
        // The in-game /mail clear is the same composition, and the filter has to reach both halves or a
        // mail clear would dismiss the player's notifications too.
        addMail("m", "hello", true);

        InboxView.ActionResult result = view(MailPayload.DATA_TYPE, "Mail", 10).clear(PLAYER);

        Assertions.assertInstanceOf(InboxView.ActionResult.Ok.class, result);
        Assertions.assertEquals(List.of(
                        new FakeNotificationService.Call("markAllSeen", MailPayload.DATA_TYPE, null),
                        new FakeNotificationService.Call("dismissSeen", MailPayload.DATA_TYPE, null)),
                this.service.calls());
    }

    @Test
    void anEmptyInboxIsOnePageWithNoRows() {
        InboxView.Page page = view(null, "Notifications", 10).page(PLAYER, 1);

        Assertions.assertTrue(page.isEmpty());
        Assertions.assertEquals(1, page.totalPages(), "an empty inbox reads as page 1 of 1");
        Assertions.assertEquals(0, page.unreadCount());
    }

    @Test
    void aPayloadWithNoRegisteredRendererShowsThePlaceholderRatherThanVanishing() {
        this.service.add(new InboxEntry("x", Instant.EPOCH, null, "from-a-removed-module", "{}", 0, null));

        InboxView.Page page = view(null, "Notifications", 10).page(PLAYER, 1);

        Assertions.assertEquals(1, page.rows().size(), "a hidden row would still be counted and read as a bug");
        Assertions.assertTrue(page.rows().get(0).body().contains("from-a-removed-module"));
    }
}
