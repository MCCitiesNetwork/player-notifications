package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.components.ActionComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class PreferenceMessageFactoryTest {

    private static final PreferenceMessageFactory FACTORY = new PreferenceMessageFactory(0x5865F2);

    private static PreferenceView.State state(int pending, boolean muted) {
        return new PreferenceView.State("mail",
                List.of(new PreferenceView.Choice("mail", "Mail", true),
                        new PreferenceView.Choice("test", "Test", false)),
                List.of(new PreferenceView.Choice("chat", "Chat", true),
                        new PreferenceView.Choice("discord-dm", "Discord Dm", false),
                        new PreferenceView.Choice("none", "Silence this type", false)),
                Set.of("chat"), muted, pending, false);
    }

    private static List<StringSelectMenu> selectsOf(MessageCreateData message) {
        return message.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .map(ActionRow.class::cast)
                .flatMap(row -> row.getComponents().stream())
                .filter(StringSelectMenu.class::isInstance)
                .map(StringSelectMenu.class::cast)
                .toList();
    }

    private static List<Button> buttonsOf(MessageCreateData message) {
        return message.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .map(ActionRow.class::cast)
                .flatMap(row -> row.getButtons().stream())
                .toList();
    }

    private static String actionOf(ActionComponent component) {
        return ComponentIds.parse(String.valueOf(component.getCustomId())).orElseThrow().action();
    }

    @Test
    void theTypeSelectOffersEveryTypeWithTheCurrentOneMarked() {
        StringSelectMenu types = selectsOf(FACTORY.preferences(state(0, false))).get(0);

        Assertions.assertEquals(List.of("mail", "test"),
                types.getOptions().stream().map(SelectOption::getValue).toList());
        Assertions.assertEquals(List.of("mail"),
                types.getOptions().stream().filter(SelectOption::isDefault)
                        .map(SelectOption::getValue).toList());
    }

    @Test
    void theMediaSelectAllowsPickingNoneOrAll() {
        // Zero is a real answer here — ticking nothing is how a player silences one type.
        StringSelectMenu media = selectsOf(FACTORY.preferences(state(0, false))).get(1);

        Assertions.assertEquals(0, media.getMinValues());
        Assertions.assertEquals(3, media.getMaxValues());
        Assertions.assertEquals(List.of("chat"),
                media.getOptions().stream().filter(SelectOption::isDefault)
                        .map(SelectOption::getValue).toList());
    }

    @Test
    void applyAndDiscardAreAlwaysOffered() {
        List<Button> buttons = buttonsOf(FACTORY.preferences(state(0, false)));

        Assertions.assertTrue(buttons.stream().anyMatch(button -> actionOf(button).equals("apply")));
        Assertions.assertTrue(buttons.stream().anyMatch(button -> actionOf(button).equals("discard")));
    }

    @Test
    void theMuteButtonReadsAsTheActionItWouldTake() {
        List<Button> unmuted = buttonsOf(FACTORY.preferences(state(0, false)));
        Assertions.assertTrue(unmuted.stream().anyMatch(button -> button.getLabel().contains("Mute")));

        List<Button> muted = buttonsOf(FACTORY.preferences(state(0, true)));
        Assertions.assertTrue(muted.stream().anyMatch(button -> button.getLabel().contains("Unmute")));
    }

    @Test
    void thePendingCountIsShownOnlyWhenSomethingIsPending() {
        Assertions.assertTrue(FACTORY.preferences(state(2, false)).getEmbeds().get(0)
                .getDescription().contains("2"));
        Assertions.assertFalse(FACTORY.preferences(state(0, false)).getEmbeds().get(0)
                .getDescription().contains("pending"));
    }

    @Test
    void anExpiredScreenSaysSoAndOffersNothingToPress() {
        PreferenceView.State expired =
                new PreferenceView.State("", List.of(), List.of(), Set.of(), false, 0, true);

        MessageCreateData message = FACTORY.preferences(expired);

        Assertions.assertEquals(List.of(), buttonsOf(message));
        Assertions.assertEquals(List.of(), selectsOf(message));
        Assertions.assertTrue(message.getContent().contains("expired")
                || message.getEmbeds().get(0).getDescription().contains("expired"));
    }

    @Test
    void moreOptionsThanDiscordAllowsAreTruncatedRatherThanRejected() {
        List<PreferenceView.Choice> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new PreferenceView.Choice("type-" + i, "Type " + i, i == 0));
        }
        PreferenceView.State state = new PreferenceView.State("type-0", many, many, Set.of(), false, 0, false);

        List<StringSelectMenu> selects = selectsOf(FACTORY.preferences(state));

        Assertions.assertTrue(selects.get(0).getOptions().size() <= 25);
        Assertions.assertTrue(selects.get(1).getOptions().size() <= 25);
    }

    @Test
    void everyComponentIdIsOneWeCanParseBack() {
        MessageCreateData message = FACTORY.preferences(state(1, false));

        for (Object component : message.getComponents()) {
            for (ActionComponent child : ((ActionRow) component).getActionComponents()) {
                Assertions.assertTrue(
                        ComponentIds.parse(String.valueOf(child.getCustomId())).isPresent(),
                        "unparseable id: " + child.getCustomId());
            }
        }
    }
}
