package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class InboxMessageFactoryTest {

    private static final InboxMessageFactory FACTORY = new InboxMessageFactory(0x5865F2);

    private static InboxView.Page page(int page, int totalPages, InboxView.Row... rows) {
        return new InboxView.Page("Notifications", List.of(rows), page, totalPages, rows.length,
                (int) List.of(rows).stream().filter(InboxView.Row::unread).count());
    }

    private static InboxView.Row row(int entry, String key, boolean unread) {
        return new InboxView.Row(entry, key, unread, "Title " + entry, "Body " + entry);
    }

    private static Optional<StringSelectMenu> selectOf(MessageCreateData message) {
        return message.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .map(ActionRow.class::cast)
                .flatMap(row -> row.getComponents().stream())
                .filter(StringSelectMenu.class::isInstance)
                .map(StringSelectMenu.class::cast)
                .findFirst();
    }

    private static List<Button> buttonsOf(MessageCreateData message) {
        return message.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .map(ActionRow.class::cast)
                .flatMap(row -> row.getButtons().stream())
                .toList();
    }

    private static Button button(MessageCreateData message, String action) {
        return buttonsOf(message).stream()
                .filter(candidate -> ComponentIds.parse(String.valueOf(candidate.getCustomId()))
                        .filter(parsed -> parsed.action().equals(action)).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + action + "' button"));
    }

    @Test
    void aPageIsOneEmbedWithARowPerEntry() {
        MessageCreateData message = FACTORY.listing(
                page(1, 1, row(1, "a", true), row(2, "b", false)), ComponentIds.SURFACE_INBOX);

        MessageEmbed embed = message.getEmbeds().get(0);
        Assertions.assertTrue(embed.getTitle().contains("Notifications"));
        Assertions.assertTrue(embed.getTitle().contains("1"), "the page number is on show");
        Assertions.assertTrue(embed.getDescription().contains("Title 1"));
        Assertions.assertTrue(embed.getDescription().contains("Title 2"));
    }

    @Test
    void anUnreadRowIsMarkedDifferentlyFromAReadOne() {
        MessageCreateData message =
                FACTORY.listing(page(1, 1, row(1, "a", true), row(2, "b", false)), ComponentIds.SURFACE_INBOX);

        String description = message.getEmbeds().get(0).getDescription();
        String unreadLine = description.lines().filter(line -> line.contains("Title 1")).findFirst().orElseThrow();
        String readLine = description.lines().filter(line -> line.contains("Title 2")).findFirst().orElseThrow();
        Assertions.assertNotEquals(unreadLine.replace("Title 1", ""), readLine.replace("Title 2", ""),
                "unread and read rows must not look identical");
    }

    @Test
    void eachRowIsSelectableByItsOwnNotificationKey() {
        MessageCreateData message =
                FACTORY.listing(page(1, 1, row(1, "mail-1", true), row(2, "mail-2", true)),
                        ComponentIds.SURFACE_INBOX_MAIL);

        StringSelectMenu select = selectOf(message).orElseThrow();
        Assertions.assertEquals(List.of("mail-1", "mail-2"),
                select.getOptions().stream().map(SelectOption::getValue).toList());
        ComponentIds.Parsed parsed = ComponentIds.parse(select.getCustomId()).orElseThrow();
        Assertions.assertEquals(ComponentIds.SURFACE_INBOX_MAIL, parsed.surface(),
                "a mail listing must not be answered by the unfiltered view");
    }

    @Test
    void previousIsDisabledOnTheFirstPageAndNextOnTheLast() {
        MessageCreateData first = FACTORY.listing(page(1, 3, row(1, "a", true)), ComponentIds.SURFACE_INBOX);
        Assertions.assertTrue(button(first, "prev").isDisabled());
        Assertions.assertFalse(button(first, "next").isDisabled());

        MessageCreateData last = FACTORY.listing(page(3, 3, row(1, "a", true)), ComponentIds.SURFACE_INBOX);
        Assertions.assertFalse(button(last, "prev").isDisabled());
        Assertions.assertTrue(button(last, "next").isDisabled());
    }

    @Test
    void thePagingButtonsCarryThePageTheyGoTo() {
        MessageCreateData message = FACTORY.listing(page(2, 3, row(1, "a", true)), ComponentIds.SURFACE_INBOX);

        Assertions.assertEquals(1,
                ComponentIds.parse(button(message, "prev").getCustomId()).orElseThrow().intArg(0).orElseThrow());
        Assertions.assertEquals(3,
                ComponentIds.parse(button(message, "next").getCustomId()).orElseThrow().intArg(0).orElseThrow());
    }

    @Test
    void anEmptyPageCarriesNoComponentsAtAll() {
        // Discord rejects an empty select the same way vanilla rejects an empty multi-action dialog:
        // the message would fail to send rather than render with nothing on it.
        MessageCreateData message = FACTORY.listing(page(1, 1), ComponentIds.SURFACE_INBOX);

        Assertions.assertTrue(selectOf(message).isEmpty());
        Assertions.assertEquals(List.of(), buttonsOf(message));
        Assertions.assertFalse(message.getEmbeds().get(0).getDescription().isBlank(),
                "the player still has to be told the inbox is empty");
    }

    @Test
    void aDetailShowsTheEntryWithDismissMarkUnreadAndBack() {
        MessageCreateData message = FACTORY.detail(row(1, "mail-1", true), ComponentIds.SURFACE_INBOX_MAIL);

        Assertions.assertEquals("Title 1", message.getEmbeds().get(0).getTitle());
        Assertions.assertEquals("Body 1", message.getEmbeds().get(0).getDescription());
        Assertions.assertEquals("mail-1",
                ComponentIds.parse(button(message, "dismiss").getCustomId()).orElseThrow().arg(0).orElseThrow());
        Assertions.assertEquals("mail-1",
                ComponentIds.parse(button(message, "unread").getCustomId()).orElseThrow().arg(0).orElseThrow());
        Assertions.assertNotNull(button(message, "page"), "there is a way back to the listing");
    }

    @Test
    void anOverLongBodyIsTruncatedRatherThanRejectedByJda() {
        // JDA's builders throw on overlong input instead of trimming, so a long mail would otherwise
        // fail to render at all.
        InboxView.Row long_ = new InboxView.Row(1, "a", true, "t".repeat(400), "b".repeat(6000));

        MessageEmbed embed = FACTORY.detail(long_, ComponentIds.SURFACE_INBOX).getEmbeds().get(0);

        Assertions.assertTrue(embed.getTitle().length() <= MessageEmbed.TITLE_MAX_LENGTH);
        Assertions.assertTrue(embed.getDescription().length() <= MessageEmbed.DESCRIPTION_MAX_LENGTH);
    }

    @Test
    void aPageWithMoreRowsThanDiscordAllowsInASelectStillRenders() {
        InboxView.Row[] rows = new InboxView.Row[30];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(i + 1, "key-" + i, true);
        }

        MessageCreateData message = FACTORY.listing(page(1, 1, rows), ComponentIds.SURFACE_INBOX);

        Assertions.assertTrue(selectOf(message).orElseThrow().getOptions().size() <= 25);
    }

    @Test
    void everyComponentIdIsOneWeCanParseBack() {
        MessageCreateData message =
                FACTORY.listing(page(2, 3, row(1, "a", true)), ComponentIds.SURFACE_INBOX);

        for (MessageTopLevelComponent component : message.getComponents()) {
            for (Object child : ((ActionRow) component).getActionComponents()) {
                String id = ((net.dv8tion.jda.api.components.ActionComponent) child).getCustomId();
                Assertions.assertTrue(ComponentIds.parse(String.valueOf(id)).isPresent(),
                        "unparseable id: " + id);
            }
        }
    }
}
