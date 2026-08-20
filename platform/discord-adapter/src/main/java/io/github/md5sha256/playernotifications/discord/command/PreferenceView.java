package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Editing one player's delivery preferences from Discord, staged exactly as the in-game dialogs stage
 * them: pick a {@code dataType}, tick its media, and nothing is written until Apply.
 *
 * <p>Backed by the host's own {@link PreferenceEditSession}, but held in a session manager this module
 * owns rather than the host's. Sharing would make an in-game staged edit visible here, which sounds
 * desirable and is not: the dialog screens' "Back abandons this screen's checkboxes" behaviour assumes
 * one owner of the session, and an Apply from Discord would commit a half-finished dialog edit with
 * nothing on the player's screen changing to say so. Two managers means two independent staged edits,
 * last Apply wins.
 *
 * <p>Every method blocks on JDBC; call them off the main thread.
 */
public final class PreferenceView {

    /** Discord rejects a select carrying more options than this. */
    public static final int MAX_CHOICES = 25;

    private final DatabaseNotificationPreferences preferences;
    private final NotificationSinkRegistry sinks;
    private final Supplier<Set<String>> knownDataTypes;
    private final PreferenceSessionManager sessions;
    private final Logger logger;

    /**
     * No clock seam: {@link PreferenceSessionManager} stamps and expires sessions with
     * {@code Instant.now()}, so a clock here could only disagree with the thing that decides whether a
     * session is still alive.
     */
    public PreferenceView(@NotNull DatabaseNotificationPreferences preferences,
                          @NotNull NotificationSinkRegistry sinks,
                          @NotNull Supplier<Set<String>> knownDataTypes,
                          @NotNull PreferenceSessionManager sessions,
                          @NotNull Logger logger) {
        this.preferences = preferences;
        this.sinks = sinks;
        this.knownDataTypes = knownDataTypes;
        this.sessions = sessions;
        this.logger = logger;
    }

    /** Opens (or resumes) the player's staged edit, showing {@code selectedDataType} or the first one. */
    public @NotNull State open(@NotNull UUID player, @Nullable String selectedDataType) {
        List<String> dataTypes = dataTypes();
        if (dataTypes.isEmpty()) {
            // Nothing registered any payload type, so there is nothing to configure.
            return new State("", List.of(), List.of(), Set.of(), false,
                    this.preferences.isMuted(player), 0, false);
        }
        PreferenceEditSession session = this.sessions.getOrCreate(player, () -> newSession(player, dataTypes));
        String selected = selectedDataType != null && dataTypes.contains(selectedDataType)
                ? selectedDataType
                : dataTypes.get(0);
        return state(session, dataTypes, selected);
    }

    /** Shows another data type's media. Switching rows is not an edit and stages nothing. */
    public @NotNull State selectDataType(@NotNull UUID player, @NotNull String dataType) {
        return withSession(player, session -> state(session, dataTypes(), dataType));
    }

    /**
     * Stages this data type's media. An empty selection silences the type, exactly as unticking every
     * box does in game.
     *
     * <p>{@link NotificationPreferences#SILENCED_MEDIUM} is <em>filtered out</em> rather than treated as
     * a selection. It used to be offered as a row in this select, where ticking it alongside a real
     * medium silently discarded the real one — the player was shown a silenced type with no sign their
     * other tick had been thrown away. Silencing is the {@code Silence this type} button now, so the
     * key can only arrive here from a message built before that change.
     */
    public @NotNull State setMedia(@NotNull UUID player, @NotNull String dataType,
                                   @NotNull Set<String> media) {
        return withSession(player, session -> {
            Set<String> staged = new LinkedHashSet<>(media);
            staged.remove(NotificationPreferences.SILENCED_MEDIUM);
            session.setDataTypeMedia(dataType, Set.copyOf(staged), Instant.now());
            return state(session, dataTypes(), dataType);
        });
    }

    /**
     * Stages a silence for one data type: no medium at all, so nothing of that type is ever pushed.
     * Distinct from the player-level mute, which is temporary, covers every type at once, and is set
     * only by {@code /notifications mute}.
     */
    public @NotNull State silenceDataType(@NotNull UUID player, @NotNull String dataType) {
        return withSession(player, session -> {
            session.setDataTypeMedia(dataType, Set.of(), Instant.now());
            return state(session, dataTypes(), dataType);
        });
    }

    /**
     * Stages a silence for <em>every</em> known data type. One-way by construction: it writes an
     * explicit "no media" row per type, and there is no player-facing way back to the server default
     * (in Discord or in game), so undoing it means picking media for each type again. Staging is the
     * safety net — nothing is written until Apply, and Discard puts everything back.
     *
     * <p>The row on show survives the press, so the next media edit cannot land on a different type.
     */
    public @NotNull State silenceEverything(@NotNull UUID player, @NotNull String selectedDataType) {
        return withSession(player, session -> {
            List<String> dataTypes = dataTypes();
            Instant now = Instant.now();
            for (String dataType : dataTypes) {
                session.setDataTypeMedia(dataType, Set.of(), now);
            }
            String selected = dataTypes.contains(selectedDataType)
                    ? selectedDataType
                    : dataTypes.get(0);
            return state(session, dataTypes, selected);
        });
    }

    /**
     * Writes every staged edit in one transaction and drops the session. The reset set is always empty:
     * there is no player-facing way back to the server default, in Discord or in game. The staged mute
     * is always {@code null} from this surface — no button here sets it — but it is still passed, so a
     * session staged elsewhere in this module would commit with the rest rather than be dropped.
     */
    public @NotNull String apply(@NotNull UUID player) {
        Optional<PreferenceEditSession> session = this.sessions.get(player);
        if (session.isEmpty()) {
            return "There was nothing to apply.";
        }
        int changes = session.get().dirtyCount();
        if (changes == 0) {
            this.sessions.drop(player);
            return "There was nothing to apply.";
        }
        this.preferences.applyChanges(player, session.get().explicitChanges(), Set.of(),
                session.get().stagedMuteChange());
        this.sessions.drop(player);
        return "Applied " + changes + " change" + (changes == 1 ? "" : "s") + ".";
    }

    /** Throws the staged edit away. */
    public @NotNull String discard(@NotNull UUID player) {
        this.sessions.drop(player);
        return "Discarded your unsaved changes.";
    }

    /** Sets the player-level mute immediately, discarding any staged session — the command form. */
    public void muteImmediately(@NotNull UUID player) {
        this.sessions.drop(player);
        this.preferences.mute(player);
    }

    /** Clears the player-level mute immediately, discarding any staged session — the command form. */
    public void unmuteImmediately(@NotNull UUID player) {
        this.sessions.drop(player);
        this.preferences.unmute(player);
    }

    private @NotNull State withSession(@NotNull UUID player,
                                       @NotNull java.util.function.Function<PreferenceEditSession, State> action) {
        Optional<PreferenceEditSession> session = this.sessions.get(player);
        if (session.isEmpty()) {
            // The ephemeral message outlived its session; staging against a matrix the player can no
            // longer see would apply edits they never made.
            return new State("", List.of(), List.of(), Set.of(), false,
                    this.preferences.isMuted(player), 0, true);
        }
        return action.apply(session.get());
    }

    private @NotNull PreferenceEditSession newSession(@NotNull UUID player, @NotNull List<String> dataTypes) {
        return new PreferenceEditSession(player,
                this.preferences.effectiveMediaByDataType(player, Set.copyOf(dataTypes)),
                this.preferences.isMuted(player),
                Instant.now());
    }

    private @NotNull List<String> dataTypes() {
        List<String> sorted = new ArrayList<>(new TreeSet<>(this.knownDataTypes.get()));
        if (sorted.size() > MAX_CHOICES) {
            this.logger.warning("This server has " + sorted.size() + " notification types; the Discord"
                    + " preference screen can only show " + MAX_CHOICES
                    + ", so the rest are only configurable in game");
            return List.copyOf(sorted.subList(0, MAX_CHOICES));
        }
        return List.copyOf(sorted);
    }

    private @NotNull State state(@NotNull PreferenceEditSession session, @NotNull List<String> dataTypes,
                                 @NotNull String selected) {
        Set<String> selectedMedia = session.mediaFor(selected);
        List<Choice> typeChoices = new ArrayList<>(dataTypes.size());
        for (String dataType : dataTypes) {
            typeChoices.add(new Choice(dataType, label(dataType), dataType.equals(selected)));
        }

        List<Choice> mediaChoices = new ArrayList<>();
        for (String medium : mediaKeys()) {
            mediaChoices.add(new Choice(medium,
                    PlainTextComponentSerializer.plainText().serialize(this.sinks.displayName(medium)),
                    selectedMedia.contains(medium)));
        }
        // The silence is a button, not a row: as an option it competed with the real media, and a
        // player who ticked one of each had the real medium dropped without being told.
        boolean silenced = selectedMedia.isEmpty()
                || selectedMedia.contains(NotificationPreferences.SILENCED_MEDIUM);

        return new State(selected, List.copyOf(typeChoices), List.copyOf(mediaChoices),
                Set.copyOf(selectedMedia), silenced, session.muted(), session.dirtyCount(), false);
    }

    private @NotNull List<String> mediaKeys() {
        Set<String> media = new LinkedHashSet<>(new TreeSet<>(this.sinks.registeredMedia()));
        List<String> keys = new ArrayList<>(media);
        return keys.size() > MAX_CHOICES ? keys.subList(0, MAX_CHOICES) : keys;
    }

    /** Title-cased, matching how the in-game dialogs label a data type rather than showing a raw key. */
    private static @NotNull String label(@NotNull String dataType) {
        String spaced = dataType.replace('-', ' ').replace('_', ' ');
        StringBuilder builder = new StringBuilder(spaced.length());
        boolean capitalise = true;
        for (char character : spaced.toCharArray()) {
            builder.append(capitalise ? Character.toUpperCase(character) : character);
            capitalise = character == ' ';
        }
        return builder.toString();
    }

    /**
     * Everything one preference screen shows. {@code silenced} is the selected type's own state — no
     * medium staged for it — while {@code muted} is the player-level, all-types suspension, which this
     * screen only reports: it is set by {@code /notifications mute}, never by a button here.
     */
    public record State(@NotNull String selectedDataType, @NotNull List<Choice> dataTypes,
                        @NotNull List<Choice> media, @NotNull Set<String> selectedMedia,
                        boolean silenced, boolean muted, int pendingChanges, boolean expired) {
    }

    /** One select option. */
    public record Choice(@NotNull String key, @NotNull String label, boolean selected) {
    }
}
