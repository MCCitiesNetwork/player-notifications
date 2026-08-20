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
     * The tag groups, split cosmetic vs. active. The default-allowed groups can only change how text
     * looks — something a player already controls in chat. The rest can attach a runnable command or a
     * hover payload to text landing in someone else's inbox, or pull server-side state into it;
     * {@code font} is here too, since a client-side font can render text unreadably or misleadingly.
     */
    private static final List<Group> GROUPS = List.of(
            new Group("color", true, TagResolver.resolver(StandardTags.color(), StandardTags.shadowColor())),
            new Group("decoration", true, StandardTags.decorations()),
            new Group("gradient", true, TagResolver.resolver(
                    StandardTags.gradient(), StandardTags.transition(), StandardTags.pride())),
            new Group("rainbow", true, StandardTags.rainbow()),
            new Group("reset", true, StandardTags.reset()),
            new Group("newline", true, StandardTags.newline()),
            new Group("font", false, StandardTags.font()),
            new Group("click", false, StandardTags.clickEvent()),
            new Group("hover", false, StandardTags.hoverEvent()),
            new Group("insertion", false, StandardTags.insertion()),
            new Group("keybind", false, StandardTags.keybind()),
            new Group("translatable", false, TagResolver.resolver(
                    StandardTags.translatable(), StandardTags.translatableFallback())),
            new Group("selector", false, StandardTags.selector()),
            new Group("score", false, StandardTags.score()),
            new Group("nbt", false, StandardTags.nbt()));

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
     * One permission-gated set of MiniMessage tags.
     *
     * @param node           the permission node suffix, appended to {@link #PERMISSION_PREFIX}
     * @param defaultAllowed whether {@code paper-plugin.yml} grants this node by default
     * @param resolver       the tags the node unlocks
     */
    public record Group(@NotNull String node, boolean defaultAllowed, @NotNull TagResolver resolver) {
    }
}
