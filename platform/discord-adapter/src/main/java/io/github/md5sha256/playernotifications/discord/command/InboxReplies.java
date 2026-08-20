package io.github.md5sha256.playernotifications.discord.command;

import org.jetbrains.annotations.NotNull;

/** The chat replies both inbox listeners give for an {@link InboxView} result. */
final class InboxReplies {

    private InboxReplies() {
    }

    static @NotNull String of(@NotNull InboxView.ActionResult result) {
        return result instanceof InboxView.ActionResult.Ok ok
                ? ok.message()
                : outOfRange(((InboxView.ActionResult.OutOfRange) result).entry(),
                        ((InboxView.ActionResult.OutOfRange) result).rowCount());
    }

    /**
     * Names what the page actually holds. The row asked for is gone, which means the inbox changed
     * under the player — without the real count they cannot tell what to ask for instead.
     */
    static @NotNull String outOfRange(int entry, int rowCount) {
        return rowCount == 0
                ? "There is nothing on that page."
                : "There is no entry " + entry + " on that page; it has " + rowCount + ".";
    }
}
