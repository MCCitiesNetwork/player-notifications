package io.github.md5sha256.playernotifications.paper.preferences;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import io.github.md5sha256.playernotifications.paper.localisation.TypeNames;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the six preference dialog screens and the single {@link PreferenceSessionManager} they share,
 * and is the one object {@code NotificationsCommand} and {@code PreferenceQuitListener} need to hold.
 */
public final class PreferenceDialogRouter {

    private final MessageContainer messages;
    private final Plugin plugin;
    private final NotificationSinkRegistry sinkRegistry;
    private volatile NotificationCategories categories;
    private final NotificationDataTypeRegistry dataTypeRegistry;
    private final TypeNames typeNames;
    private final DatabaseNotificationPreferences preferences;
    private final PreferenceSessionManager sessions;

    private final PreferenceRootDialog rootDialog;
    private final MediumPickerDialog mediumPickerDialog;
    private final MediumEditorDialog mediumEditorDialog;
    private final CategoryPickerDialog categoryPickerDialog;
    private final CategoryEditorDialog categoryEditorDialog;
    private final MuteConfirmDialog muteConfirmDialog;

    public PreferenceDialogRouter(@NotNull MessageContainer messages,
                                  @NotNull Plugin plugin,
                                  @NotNull NotificationSinkRegistry sinkRegistry,
                                  @NotNull NotificationCategories categories,
                                  @NotNull NotificationDataTypeRegistry dataTypeRegistry,
                                  @NotNull TypeNames typeNames,
                                  @NotNull DatabaseNotificationPreferences preferences) {
        this.messages = messages;
        this.plugin = plugin;
        this.sinkRegistry = sinkRegistry;
        this.categories = categories;
        this.dataTypeRegistry = dataTypeRegistry;
        this.typeNames = typeNames;
        this.preferences = preferences;
        this.sessions = new PreferenceSessionManager();
        this.rootDialog = new PreferenceRootDialog(this);
        this.mediumPickerDialog = new MediumPickerDialog(this);
        this.mediumEditorDialog = new MediumEditorDialog(this);
        this.categoryPickerDialog = new CategoryPickerDialog(this);
        this.categoryEditorDialog = new CategoryEditorDialog(this);
        this.muteConfirmDialog = new MuteConfirmDialog(this);
    }

    @NotNull
    Plugin plugin() {
        return this.plugin;
    }

    @NotNull
    NotificationSinkRegistry sinkRegistry() {
        return this.sinkRegistry;
    }

    @NotNull
    NotificationCategories categories() {
        return this.categories;
    }

    @NotNull
    NotificationDataTypeRegistry dataTypeRegistry() {
        return this.dataTypeRegistry;
    }

    /**
     * Resolves a {@code dataType} to the name players see. Not {@code volatile} like
     * {@link #categories}: {@code TypeNames} is reloaded in place rather than replaced, so this
     * reference stays correct across {@code /notifications reload}.
     */
    @NotNull
    TypeNames typeNames() {
        return this.typeNames;
    }

    /**
     * Swaps in a freshly loaded {@link NotificationCategories}, e.g. after {@code categories.yml} is
     * reloaded. Sessions already staged with the old category set are left as-is — their category keys
     * remain valid strings to write, even if a reload renamed or removed the category they belonged to.
     */
    public void reloadCategories(@NotNull NotificationCategories categories) {
        this.categories = categories;
    }

    @NotNull
    public PreferenceSessionManager sessions() {
        return this.sessions;
    }

    public void openRoot(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.rootDialog.show(player, session));
    }

    public void openMediaPicker(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.mediumPickerDialog.show(player, session));
    }

    public void openCategoryPicker(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.categoryPickerDialog.show(player, session));
    }

    /**
     * Reopens one editor with a freshly loaded session. Used by that editor's own Discard, so the
     * reverted checkboxes are visible rather than the player being dropped somewhere else and left to
     * infer what happened.
     */
    public void openMediaEditor(@NotNull Player player, @NotNull String medium) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.mediumEditorDialog.show(player, session, medium));
    }

    public void openCategoryEditor(@NotNull Player player, @NotNull String category) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.categoryEditorDialog.show(player, session, category));
    }

    void showRoot(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.rootDialog.show(player, session);
    }

    void showMuteConfirm(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.muteConfirmDialog.show(player, session);
    }

    void showMediaPicker(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.mediumPickerDialog.show(player, session);
    }

    void showMediaEditor(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String medium) {
        this.mediumEditorDialog.show(player, session, medium);
    }

    void showCategoryPicker(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.categoryPickerDialog.show(player, session);
    }

    void showCategoryEditor(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String category) {
        this.categoryEditorDialog.show(player, session, category);
    }

    /**
     * Persists the staged session and drops it, then runs {@code onSaved} on the main thread — the
     * screen the player pressed Apply on, reopened from the database so it shows what was actually
     * written. A failure leaves the session staged so the edits are not lost to a transient database
     * error, and does not reopen anything.
     */
    void apply(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull Runnable onSaved) {
        Map<String, Set<String>> explicit = session.explicitChanges();
        Boolean mutedChange = session.stagedMuteChange();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.preferences.applyChanges(uuid, explicit, Set.of(), mutedChange);
            } catch (RuntimeException ex) {
                this.plugin.getLogger().warning(
                        "Failed to apply notification preferences for " + uuid + ": " + ex.getMessage());
                PreferenceDialogs.message(this.plugin, player,
                        this.messages.messageFor(MessageKeys.PREFERENCES_SAVE_FAILED));
                return;
            }
            this.sessions.drop(uuid);
            PreferenceDialogs.message(this.plugin, player,
                    this.messages.messageFor(MessageKeys.PREFERENCES_SAVED));
            PreferenceDialogs.onMainThread(this.plugin, player, onSaved);
        });
    }

    /**
     * Throws the staged session away and reopens the calling screen, which then reloads from the
     * database. Reopening rather than simply closing is the point: a Discard that left the player
     * looking at nothing gave no sign the values had gone back to what was stored.
     */
    void discard(@NotNull Player player, @NotNull Runnable reopen) {
        this.sessions.drop(player.getUniqueId());
        PreferenceDialogs.message(this.plugin, player,
                this.messages.messageFor(MessageKeys.PREFERENCES_DISCARDED));
        PreferenceDialogs.onMainThread(this.plugin, player, reopen);
    }

    /**
     * Immediately sets the player-level mute flag and discards any staged, unapplied session — the one
     * deliberate asymmetry with {@link MuteConfirmDialog}, which stages the same mute and requires an
     * Apply. Leaves every per-{@code dataType} preference row untouched, so unmuting restores exactly
     * what the player had.
     */
    public void muteImmediately(@NotNull Player player) {
        setMutedImmediately(player, true,
                MessageKeys.PREFERENCES_MUTED, MessageKeys.PREFERENCES_MUTE_FAILED);
    }

    /**
     * Clears the player-level mute flag and discards any staged, unapplied session, the mirror of
     * {@link #muteImmediately}.
     */
    public void unmuteImmediately(@NotNull Player player) {
        setMutedImmediately(player, false,
                MessageKeys.PREFERENCES_UNMUTED, MessageKeys.PREFERENCES_UNMUTE_FAILED);
    }

    /**
     * @param successKey the reply when the write lands, and {@code failureKey} when it does not — a key
     *                   per direction rather than one with the verb substituted into it, which is this
     *                   repo's standing rule for text that varies per call and the reason
     *                   {@code join.unread-one} and {@code -many} are already separate keys
     */
    private void setMutedImmediately(@NotNull Player player, boolean muted,
                                     @NotNull String successKey, @NotNull String failureKey) {
        UUID uuid = player.getUniqueId();
        boolean hadSession = this.sessions.get(uuid).isPresent();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                if (muted) {
                    this.preferences.mute(uuid);
                } else {
                    this.preferences.unmute(uuid);
                }
            } catch (RuntimeException ex) {
                this.plugin.getLogger().warning(
                        "Failed to " + (muted ? "mute" : "unmute") + " notifications for " + uuid + ": "
                                + ex.getMessage());
                PreferenceDialogs.message(this.plugin, player,
                        this.messages.messageFor(failureKey));
                return;
            }
            this.sessions.drop(uuid);
            Component message = this.messages.messageFor(successKey);
            if (hadSession) {
                message = message.append(
                        this.messages.messageFor(MessageKeys.PREFERENCES_SESSION_DISCARDED));
            }
            PreferenceDialogs.message(this.plugin, player, message);
        });
    }

}
