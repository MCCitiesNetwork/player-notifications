package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

class PreferenceViewTest {

    private static final Logger LOGGER = Logger.getLogger(PreferenceViewTest.class.getName());
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** One recorded {@code applyChanges} call. */
    record Applied(Map<String, Set<String>> media, Set<String> reset, Boolean muted) {
    }

    /**
     * The real class with its database calls replaced. Subclassed rather than hidden behind a new
     * interface: the production wiring hands {@code PreferenceView} the host's real instance, and a
     * narrower seam here would not exercise the same method set.
     */
    private static final class FakePreferences extends DatabaseNotificationPreferences {

        private final Map<String, Set<String>> stored = new HashMap<>();
        private final List<Applied> applied = new ArrayList<>();
        private boolean muted;

        private FakePreferences() {
            super(null, Set.of("chat"));
        }

        @Override
        public @NotNull Map<String, Set<String>> effectiveMediaByDataType(@NotNull UUID player,
                                                                         @NotNull Set<String> dataTypes) {
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String dataType : dataTypes) {
                result.put(dataType, this.stored.getOrDefault(dataType, Set.of("chat")));
            }
            return result;
        }

        @Override
        public boolean isMuted(@NotNull UUID player) {
            return this.muted;
        }

        @Override
        public void applyChanges(@NotNull UUID player, @NotNull Map<String, Set<String>> explicitMedia,
                                 @NotNull Set<String> dataTypesToReset, Boolean muted) {
            this.applied.add(new Applied(Map.copyOf(explicitMedia), Set.copyOf(dataTypesToReset), muted));
            this.stored.putAll(explicitMedia);
            if (muted != null) {
                this.muted = muted;
            }
        }

        @Override
        public void mute(@NotNull UUID player) {
            this.muted = true;
        }

        @Override
        public void unmute(@NotNull UUID player) {
            this.muted = false;
        }
    }

    private static final class NamedSink implements NotificationSink {

        private final String key;

        private NamedSink(String key) {
            this.key = key;
        }

        @Override
        public @NotNull String mediumKey() {
            return this.key;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            return DeliveryResult.DELIVERED;
        }
    }

    private FakePreferences preferences;
    private NotificationSinkRegistry sinks;
    private Set<String> dataTypes;

    @BeforeEach
    void setUp() {
        this.preferences = new FakePreferences();
        this.sinks = new NotificationSinkRegistry();
        this.sinks.registerSink(new NamedSink("chat"));
        this.sinks.registerSink(new NamedSink("discord-dm"));
        this.dataTypes = new TreeSet<>(Set.of("mail", "test"));
    }

    private PreferenceView view() {
        return new PreferenceView(this.preferences, this.sinks, () -> this.dataTypes,
                new PreferenceSessionManager(), LOGGER);
    }

    @Test
    void openingSelectsTheFirstDataTypeAndStagesNothing() {
        PreferenceView.State state = view().open(PLAYER, null);

        Assertions.assertEquals("mail", state.selectedDataType());
        Assertions.assertEquals(List.of("mail", "test"),
                state.dataTypes().stream().map(PreferenceView.Choice::key).toList());
        Assertions.assertEquals(Set.of("chat"), state.selectedMedia());
        Assertions.assertEquals(0, state.pendingChanges());
        Assertions.assertFalse(state.expired());
    }

    @Test
    void everyRegisteredMediumIsOfferedAndTheSilenceIsNotOneOfThem() {
        // As a select row the silence competed with the real media: ticking it alongside chat dropped
        // chat without telling anyone. It is a button now, so the select carries media only.
        PreferenceView.State state = view().open(PLAYER, null);

        Assertions.assertEquals(List.of("chat", "discord-dm"),
                state.media().stream().map(PreferenceView.Choice::key).toList());
        Assertions.assertTrue(state.media().get(0).selected(), "the current selection is pre-ticked");
        Assertions.assertFalse(state.media().get(1).selected());
    }

    @Test
    void changingMediaStagesTheEditWithoutWritingIt() {
        PreferenceView view = view();
        view.open(PLAYER, null);

        PreferenceView.State state = view.setMedia(PLAYER, "mail", Set.of("discord-dm"));

        Assertions.assertEquals(Set.of("discord-dm"), state.selectedMedia());
        Assertions.assertEquals(1, state.pendingChanges());
        Assertions.assertEquals(List.of(), this.preferences.applied, "nothing is written until Apply");
    }

    @Test
    void emptyingATypeSilencesIt() {
        // Ticking nothing already says "do not send me this", so it is stored as the silence row rather
        // than as zero rows, which already means "has expressed no preference".
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of());

        view.apply(PLAYER);

        Assertions.assertEquals(Map.of("mail", Set.of(NotificationPreferences.SILENCED_MEDIUM)),
                this.preferences.applied.get(0).media());
    }

    @Test
    void aStaleScreenSendingTheSilenceKeyKeepsTheRealMediumItWasSentWith() {
        // The key can only arrive from a message built before the silence became a button. Dropping the
        // real medium instead — what this used to do — silently discarded a tick the player had made.
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of("chat", NotificationPreferences.SILENCED_MEDIUM));

        view.apply(PLAYER);

        Assertions.assertEquals(Map.of("mail", Set.of("chat")),
                this.preferences.applied.get(0).media(),
                "a silence is not one medium among others");
    }

    @Test
    void theSilenceButtonStagesNoMediaForTheSelectedTypeOnly() {
        PreferenceView view = view();
        view.open(PLAYER, null);

        PreferenceView.State state = view.silenceDataType(PLAYER, "mail");

        Assertions.assertTrue(state.silenced());
        Assertions.assertEquals("mail", state.selectedDataType());
        view.apply(PLAYER);
        Assertions.assertEquals(Map.of("mail", Set.of(NotificationPreferences.SILENCED_MEDIUM)),
                this.preferences.applied.get(0).media(), "only the selected type is touched");
    }

    @Test
    void silencingEverythingStagesEveryKnownTypeAndKeepsTheRowOnShow() {
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.selectDataType(PLAYER, "test");

        PreferenceView.State state = view.silenceEverything(PLAYER, "test");

        Assertions.assertEquals("test", state.selectedDataType(),
                "the next media edit must not land on a different type");
        Assertions.assertEquals(2, state.pendingChanges());
        view.apply(PLAYER);
        Assertions.assertEquals(
                Map.of("mail", Set.of(NotificationPreferences.SILENCED_MEDIUM),
                        "test", Set.of(NotificationPreferences.SILENCED_MEDIUM)),
                this.preferences.applied.get(0).media());
    }

    @Test
    void discardIsTheWayBackFromAStagedSilenceEverything() {
        // It is the only way back: a silence writes explicit rows, and no player-facing route returns a
        // data type to the server default once Apply has run.
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.silenceEverything(PLAYER, "mail");

        view.discard(PLAYER);

        Assertions.assertEquals(List.of(), this.preferences.applied);
        Assertions.assertEquals(Set.of("chat"), view.open(PLAYER, "mail").selectedMedia());
    }

    @Test
    void applyWritesEveryStagedTypeInOneCall() {
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of("discord-dm"));
        view.setMedia(PLAYER, "test", Set.of("chat", "discord-dm"));

        String reply = view.apply(PLAYER);

        Assertions.assertEquals(1, this.preferences.applied.size(), "one transaction, not two");
        Applied applied = this.preferences.applied.get(0);
        Assertions.assertEquals(Map.of("mail", Set.of("discord-dm"), "test", Set.of("chat", "discord-dm")),
                applied.media());
        Assertions.assertEquals(Set.of(), applied.reset(),
                "there is no player-facing way back to the server default");
        Assertions.assertNull(applied.muted(), "no button on this screen stages the player-level mute");
        Assertions.assertTrue(reply.contains("2"), "the reply names how many changes were applied");
    }

    @Test
    void theScreenReportsThePlayerLevelMuteWithoutOfferingToChangeIt() {
        // Muting is temporary and covers every type; this screen edits lasting per-type silences. It
        // still has to say so, or a muted player reads a screen of correct-looking media and receives
        // nothing.
        this.preferences.mute(PLAYER);

        Assertions.assertTrue(view().open(PLAYER, null).muted());
    }

    @Test
    void reopeningAfterApplyStartsClean() {
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of("discord-dm"));
        view.apply(PLAYER);

        PreferenceView.State state = view.open(PLAYER, "mail");

        Assertions.assertEquals(0, state.pendingChanges());
        Assertions.assertEquals(Set.of("discord-dm"), state.selectedMedia(),
                "the applied edit is what the reopened screen shows");
    }

    @Test
    void discardThrowsTheStagedEditAway() {
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of("discord-dm"));

        view.discard(PLAYER);

        Assertions.assertEquals(List.of(), this.preferences.applied);
        Assertions.assertEquals(Set.of("chat"), view.open(PLAYER, "mail").selectedMedia());
    }

    @Test
    void anInteractionOnAScreenWhoseSessionIsGoneReportsItRatherThanStagingBlind() {
        // An ephemeral message outlives its session; acting on it would stage an edit against a matrix
        // the player can no longer see.
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.discard(PLAYER);

        Assertions.assertTrue(view.setMedia(PLAYER, "mail", Set.of("chat")).expired());
        Assertions.assertTrue(view.silenceDataType(PLAYER, "mail").expired());
        Assertions.assertTrue(view.silenceEverything(PLAYER, "mail").expired());
        Assertions.assertTrue(view.selectDataType(PLAYER, "test").expired());
    }

    @Test
    void selectingADataTypeShowsThatTypesMediaWithoutLosingTheOtherStagedEdit() {
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of("discord-dm"));

        PreferenceView.State state = view.selectDataType(PLAYER, "test");

        Assertions.assertEquals("test", state.selectedDataType());
        Assertions.assertEquals(Set.of("chat"), state.selectedMedia());
        Assertions.assertEquals(1, state.pendingChanges(), "switching rows is not an edit");
    }

    @Test
    void mutingImmediatelyWritesAndDropsAnyStagedSession() {
        // The command form is deliberately immediate, matching /notifications mute in game.
        PreferenceView view = view();
        view.open(PLAYER, null);
        view.setMedia(PLAYER, "mail", Set.of("discord-dm"));

        view.muteImmediately(PLAYER);

        Assertions.assertTrue(this.preferences.isMuted(PLAYER));
        Assertions.assertEquals(List.of(), this.preferences.applied, "the staged media edit is dropped");
        Assertions.assertEquals(0, view.open(PLAYER, "mail").pendingChanges());

        view.unmuteImmediately(PLAYER);
        Assertions.assertFalse(this.preferences.isMuted(PLAYER));
    }

    @Test
    void moreDataTypesThanDiscordCanShowAreTruncatedRatherThanFailingTheWholeMessage() {
        // Discord rejects a select with more than 25 options, which would surface as no message at all.
        this.dataTypes = new TreeSet<>();
        for (int i = 0; i < 30; i++) {
            this.dataTypes.add(String.format("type-%02d", i));
        }

        PreferenceView.State state = view().open(PLAYER, null);

        Assertions.assertEquals(PreferenceView.MAX_CHOICES, state.dataTypes().size());
        Assertions.assertEquals("type-00", state.selectedDataType());
    }
}
