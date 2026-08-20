package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The command set is asserted here rather than against a live bot because
 * {@link SlashCommandRegistrar#onReady} is the one thing in this class that cannot run without a
 * connection. Registration lives in one place precisely so this test can exist: JDA's
 * {@code updateCommands()} replaces the whole global set, so a second registering listener would
 * silently delete these.
 */
class SlashCommandRegistrarTest {

    private static Set<String> namesOf(List<CommandData> commands) {
        return commands.stream().map(CommandData::getName).collect(Collectors.toSet());
    }

    private static SlashCommandData command(List<CommandData> commands, String name) {
        return commands.stream()
                .filter(data -> data.getName().equals(name))
                .map(SlashCommandData.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + name + " in " + namesOf(commands)));
    }

    private static SubcommandData subcommand(SlashCommandData command, String name) {
        return command.getSubcommands().stream()
                .filter(sub -> sub.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + command.getName() + " " + name));
    }

    private static OptionData option(SubcommandData subcommand, String name) {
        return subcommand.getOptions().stream()
                .filter(opt -> opt.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no option " + name + " on " + subcommand.getName()));
    }

    @Test
    void everyCommandIsRegisteredWhenCommandsAndLinkingAreEnabled() {
        Assertions.assertEquals(Set.of("link", "mail", "notifications"),
                namesOf(SlashCommandRegistrar.commandData(true, true)));
    }

    @Test
    void disablingCommandsLeavesLinkingRegistered() {
        // A server that wants Discord as a delivery medium only still needs players to be able to link.
        Assertions.assertEquals(Set.of("link"), namesOf(SlashCommandRegistrar.commandData(false, true)));
    }

    @Test
    void aDiscordSrvOnlyServerGetsNoLinkCommand() {
        // Nothing to redeem a code into without the embedded provider, so the command would be dead.
        Assertions.assertEquals(Set.of("mail", "notifications"),
                namesOf(SlashCommandRegistrar.commandData(true, false)));
    }

    @Test
    void everyCommandIsUsableInABotDmAndInAGuild() {
        for (CommandData data : SlashCommandRegistrar.commandData(true, true)) {
            Assertions.assertEquals(
                    Set.of(InteractionContextType.BOT_DM, InteractionContextType.GUILD),
                    data.getContexts(),
                    "/" + data.getName() + " must be usable from a DM with the bot and from a guild");
        }
    }

    @Test
    void mailCarriesEverySubcommandTheInGameCommandHas() {
        SlashCommandData mail = command(SlashCommandRegistrar.commandData(true, true), "mail");
        Assertions.assertEquals(
                Set.of("send", "list", "read", "dismiss", "clear"),
                mail.getSubcommands().stream().map(SubcommandData::getName).collect(Collectors.toSet()));
    }

    @Test
    void notificationsCarriesEverySubcommandTheInGameCommandHas() {
        SlashCommandData notifications =
                command(SlashCommandRegistrar.commandData(true, true), "notifications");
        Assertions.assertEquals(
                Set.of("list", "read", "dismiss", "clear", "prefs", "mute", "unmute"),
                notifications.getSubcommands().stream()
                        .map(SubcommandData::getName).collect(Collectors.toSet()));
    }

    @Test
    void mailSendTakesOnlyARecipient() {
        // The message is typed in the modal that send opens, not as a slash option.
        SubcommandData send = subcommand(command(SlashCommandRegistrar.commandData(true, true), "mail"), "send");
        Assertions.assertTrue(option(send, "player").isRequired());
        Assertions.assertEquals(List.of("player"),
                send.getOptions().stream().map(OptionData::getName).toList());
    }

    @Test
    void mailReadRequiresAnEntryAndTakesAnOptionalPage() {
        // The page is an option rather than a stored cursor: that is what makes the surface stateless.
        SubcommandData read = subcommand(command(SlashCommandRegistrar.commandData(true, true), "mail"), "read");
        Assertions.assertTrue(option(read, "entry").isRequired());
        Assertions.assertFalse(option(read, "page").isRequired());
    }

    @Test
    void linkStillCarriesItsCodeOption() {
        SlashCommandData link = command(SlashCommandRegistrar.commandData(true, true), "link");
        Assertions.assertTrue(link.getOptions().stream()
                .anyMatch(option -> option.getName().equals("code") && option.isRequired()));
    }
}
