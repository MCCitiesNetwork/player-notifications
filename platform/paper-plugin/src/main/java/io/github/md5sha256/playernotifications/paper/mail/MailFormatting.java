package io.github.md5sha256.playernotifications.paper.mail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Predicate;

/**
 * Decides which MiniMessage tags a mail sender may use, and turns a typed message into the canonical,
 * already-authorised MiniMessage document that gets stored in the payload.
 *
 * <p><b>Why this happens at send time.</b> The permission check needs the <i>sender</i>, and a
 * {@code NotificationRenderer} runs on read — potentially long after the send, with the sender offline
 * or gone. So {@code /mail send} deserializes the message with a resolver holding only the groups the
 * sender is permitted, then re-serializes the result with the standard {@link MiniMessage}. Anything
 * the sender was not allowed to use was never a tag, so it survives as text and is escaped on the way
 * out; {@code MailRenderer} can therefore parse the stored string with the full standard tag set and
 * make no trust decision of its own.
 *
 * <p><b>Why a denied tag is left literal rather than stripped.</b> Silently deleting part of someone's
 * sentence is the failure mode {@link MailRecipients} already refuses for an over-length message. The
 * recipient sees {@code <click:run_command:/op me>} verbatim, which is both honest and inert.
 *
 * <p>The gate takes a {@code Predicate<String>} over permission nodes rather than a {@code
 * CommandSender}, the same seam {@link MailRecipients} uses for name lookup, so every rule here is
 * unit-testable without a server. The console is a {@code ConsoleCommandSender} whose
 * {@code hasPermission} is unconditionally true, so it gets every group with no special case.
 */
public final class MailFormatting {

    /** Prefix of the per-group permission nodes; the full node is this plus {@link Group#node()}. */
    public static final String PERMISSION_PREFIX = "playernotifications.command.mail.format.";

    /**
     * The tag groups. <b>Every one of them is {@code default: op} in {@code paper-plugin.yml}</b> — a
     * formatted mail is something an operator opts a rank into, cosmetic tags included, so an ordinary
     * player's message is literal text until a server says otherwise.
     *
     * <p>They are split finely all the same, so a server can grant exactly the cosmetic groups
     * ({@code color}, {@code decoration}, {@code gradient}, {@code rainbow}, {@code reset},
     * {@code newline}) without also handing out click and hover events, which attach a runnable command
     * or a payload to text landing in someone else's inbox, or {@code score}/{@code nbt}, which pull
     * server-side state into it. {@code font} sits with those because a client-side font can render
     * text misleadingly.
     */
    private static final List<Group> GROUPS = List.of(
            new Group("color", TagResolver.resolver(StandardTags.color(), StandardTags.shadowColor())),
            new Group("decoration", StandardTags.decorations()),
            new Group("gradient", TagResolver.resolver(
                    StandardTags.gradient(), StandardTags.transition(), StandardTags.pride())),
            new Group("rainbow", StandardTags.rainbow()),
            new Group("reset", StandardTags.reset()),
            new Group("newline", StandardTags.newline()),
            new Group("font", StandardTags.font()),
            new Group("click", StandardTags.clickEvent()),
            new Group("hover", StandardTags.hoverEvent()),
            new Group("insertion", StandardTags.insertion()),
            new Group("keybind", StandardTags.keybind()),
            new Group("translatable", TagResolver.resolver(
                    StandardTags.translatable(), StandardTags.translatableFallback())),
            new Group("selector", StandardTags.selector()),
            new Group("score", StandardTags.score()),
            new Group("nbt", StandardTags.nbt()));

    private MailFormatting() {
    }

    /** The tag groups, in declaration order. */
    @NotNull
    public static List<Group> groups() {
        return GROUPS;
    }

    /**
     * The resolver for a sender: every group whose {@link #PERMISSION_PREFIX}-prefixed node
     * {@code hasPermission} accepts. Groups the sender lacks are simply not registered, which is what
     * makes their tags render as literal text rather than fail or vanish.
     */
    @NotNull
    public static TagResolver resolverFor(@NotNull Predicate<String> hasPermission) {
        TagResolver.Builder builder = TagResolver.builder();
        for (Group group : GROUPS) {
            if (hasPermission.test(PERMISSION_PREFIX + group.node())) {
                builder.resolver(group.resolver());
            }
        }
        return builder.build();
    }

    /**
     * Deserializes {@code raw} with {@code allowed} and re-serializes it with the standard
     * {@link MiniMessage}, yielding the string to store in the payload.
     *
     * <p>Returns {@code ""} when the message carries no readable text — {@code "<red>"} alone parses to
     * an empty component, which still <i>serializes</i> back to {@code "<red>"}, so blankness is judged
     * on the rendered text rather than on the stored string. The caller must reject that, since
     * {@code MailPayload} refuses a blank message. Malformed MiniMessage is not an error
     * in MiniMessage (it renders literally), but any {@code RuntimeException} from the parser falls
     * back to the raw text as a literal component so a parser edge case cannot fail a send.
     */
    @NotNull
    public static String sanitize(@NotNull String raw, @NotNull TagResolver allowed) {
        Component parsed;
        try {
            parsed = MiniMessage.builder().tags(allowed).build().deserialize(raw);
        } catch (RuntimeException ex) {
            parsed = Component.text(raw);
        }
        if (PlainTextComponentSerializer.plainText().serialize(parsed).isBlank()) {
            return "";
        }
        return MiniMessage.miniMessage().serialize(parsed);
    }

    /**
     * One permission-gated set of MiniMessage tags. There is deliberately no "granted by default"
     * component: every group is {@code op}, so the record would carry the same value fifteen times.
     * {@code MailFormattingTest} asserts that against {@code paper-plugin.yml} directly instead, which
     * is the file that actually decides it.
     *
     * @param node     the permission node suffix, appended to {@link #PERMISSION_PREFIX}
     * @param resolver the tags the node unlocks
     */
    public record Group(@NotNull String node, @NotNull TagResolver resolver) {
    }
}
