package io.github.md5sha256.playernotifications.essentials.convert;

import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.userstorage.IUserMap;
import net.essentialsx.api.v2.services.mail.MailMessage;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads every stored EssentialsX mailbox, flattening each message into an {@link ImportedMail}.
 *
 * <p>Together with {@link EssentialsMailBinding} this is one of only two classes in the module that
 * names an EssentialsX type; see {@link EssentialsMailConverterModule} for why that separation is
 * load-bearing rather than stylistic.
 *
 * <p><strong>The read runs on the main thread, in chunks.</strong> EssentialsX user loading is not
 * thread-safe, so it cannot be moved to the async scheduler — but loading a few thousand userdata files
 * in a single tick would stall a live server for seconds. {@link #USERS_PER_TICK} accounts are therefore
 * processed per tick until the queue drains, at which point the accumulated list is handed to the
 * callback exactly once. A 20k-account server converts in roughly ten seconds of wall clock without a
 * single long tick.
 */
final class EssentialsMailReader {

    /** Accounts loaded per tick. Chosen to keep a single run well inside a 50ms tick budget. */
    private static final int USERS_PER_TICK = 100;

    private final Plugin plugin;
    private final IEssentials essentials;
    private final Logger logger;

    EssentialsMailReader(@NotNull Plugin plugin, @NotNull IEssentials essentials, @NotNull Logger logger) {
        this.plugin = plugin;
        this.essentials = essentials;
        this.logger = logger;
    }

    /**
     * Sweeps every stored user, calling {@code onComplete} on the main thread once with everything read.
     * Must be called from the main thread.
     */
    void readAsync(@NotNull Consumer<List<ImportedMail>> onComplete) {
        IUserMap users = this.essentials.getUsers();
        Deque<UUID> queue = new ArrayDeque<>(users.getAllUserUUIDs());
        List<ImportedMail> collected = new ArrayList<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < USERS_PER_TICK && !queue.isEmpty(); i++) {
                    read(users, queue.poll(), collected);
                }
                if (queue.isEmpty()) {
                    cancel();
                    onComplete.accept(collected);
                }
            }
        }.runTaskTimer(this.plugin, 1L, 1L);
    }

    /**
     * Reads one account's mailbox. One corrupt or unreadable userdata file must not abort a sweep over
     * an entire server, so a failure is logged against its UUID and the account skipped.
     */
    private void read(@NotNull IUserMap users, @NotNull UUID uuid, @NotNull List<ImportedMail> into) {
        try {
            // loadUncachedUser, not getUser: EssentialsX documents it for exactly this kind of sweep, and
            // it does not fill the user cache with thousands of accounts read once and never again.
            User user = users.loadUncachedUser(uuid);
            if (user == null) {
                return;
            }
            Instant now = Instant.now();
            for (MailMessage message : user.getMailMessages()) {
                // A null body is mapped to the empty string rather than guarded against: the converter
                // already skips blank messages, so this needs no second rule of its own.
                String body = message.getMessage() == null ? "" : message.getMessage();
                into.add(ImportedMail.of(uuid, message.isLegacy(), message.isRead(),
                        message.getSenderUsername(), message.getSenderUUID(),
                        message.getTimeSent(), message.getTimeExpire(), body, now));
            }
        } catch (RuntimeException ex) {
            this.logger.log(Level.WARNING, "Skipping EssentialsX user " + uuid
                    + ": their mail could not be read", ex);
        }
    }
}
