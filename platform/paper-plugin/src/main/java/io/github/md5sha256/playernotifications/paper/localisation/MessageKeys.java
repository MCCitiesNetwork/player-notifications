package io.github.md5sha256.playernotifications.paper.localisation;

/**
 * Every key in {@code messages.yml}, one constant apiece.
 *
 * <p>The container renders a <strong>missing key as the key itself</strong>, so a typo reaches a
 * player as the literal text {@code mail.sent} rather than as an exception. This class is what makes
 * that catchable: {@code MessageKeysTest} walks these constants against the shipped file in both
 * directions, so a key declared here and absent there — or present there and declared nowhere — is a
 * failing test rather than a bug report.
 *
 * <p>Adding a message therefore means adding <em>both</em> a constant here and a line in
 * {@code messages.yml}.
 */
public final class MessageKeys {

    private MessageKeys() {
    }

    /**
     * Resolved by the container itself as the {@code <prefix>} tag, so nothing here reads it by name.
     * Declared anyway: the round-trip test asserts every shipped key has a constant, and an
     * undeclared {@code prefix} would fail that for a key that is genuinely in use.
     */
    public static final String PREFIX = "prefix";

    // common
    public static final String COMMON_ERROR = "common.error";

    // notifications
    public static final String NOTIFICATIONS_PLAYERS_ONLY = "notifications.players-only";

    // reload
    public static final String RELOAD_SUCCESS = "reload.success";
    public static final String RELOAD_FAILED = "reload.failed";

    // mail
    public static final String MAIL_TITLE = "mail.title";
    public static final String MAIL_PLAYERS_ONLY = "mail.players-only";
    public static final String MAIL_SENT = "mail.sent";
    public static final String MAIL_UNKNOWN_PLAYER = "mail.unknown-player";
    public static final String MAIL_BLANK_MESSAGE = "mail.blank-message";
    public static final String MAIL_MESSAGE_TOO_LONG = "mail.message-too-long";
    public static final String MAIL_ROW_PREFIX = "mail.row.prefix";
    public static final String MAIL_ROW_SENDER = "mail.row.sender";
    public static final String MAIL_ROW_CONTENT_UNREAD = "mail.row.content-unread";
    public static final String MAIL_ROW_CONTENT_READ = "mail.row.content-read";

    // broadcast
    public static final String BROADCAST_TITLE = "broadcast.title";
    public static final String BROADCAST_NO_AUDIENCE = "broadcast.no-audience";
    public static final String BROADCAST_NOTHING_ENABLED = "broadcast.nothing-enabled";
    public static final String BROADCAST_BLANK_CONTENT = "broadcast.blank-content";
    public static final String BROADCAST_FLAG_MISSING_VALUE = "broadcast.flag-missing-value";
    public static final String BROADCAST_UNRECOGNISED_TOKEN = "broadcast.unrecognised-token";
    public static final String BROADCAST_PARSE_FAILED = "broadcast.parse-failed";
    public static final String BROADCAST_SENT = "broadcast.sent";
    public static final String BROADCAST_UNKNOWN_CHAIN = "broadcast.unknown-chain";
    public static final String BROADCAST_INVALID_LIMIT = "broadcast.invalid-limit";
    public static final String BROADCAST_LIMIT_EXCEEDED = "broadcast.limit-exceeded";
    public static final String BROADCAST_OFFLINE_REQUIRES_PERSISTENT = "broadcast.offline-requires-persistent";
    public static final String BROADCAST_OFFLINE_REQUIRES_PERMISSION = "broadcast.offline-requires-permission";
    public static final String BROADCAST_OFFLINE_UNAVAILABLE = "broadcast.offline-unavailable";
    public static final String BROADCAST_LOOKUP_FAILED = "broadcast.lookup-failed";
    public static final String BROADCAST_STORED_ONE = "broadcast.stored-one";
    public static final String BROADCAST_STORED_MANY = "broadcast.stored-many";
    public static final String BROADCAST_BYPASSED = "broadcast.bypassed";

    // join
    public static final String JOIN_UNREAD_ONE = "join.unread-one";
    public static final String JOIN_UNREAD_MANY = "join.unread-many";
    public static final String JOIN_UNREAD_MAIL = "join.unread-mail";

    // inbox
    public static final String INBOX_TITLE = "inbox.title";
    public static final String INBOX_EMPTY = "inbox.empty";
    public static final String INBOX_ALREADY_EMPTY = "inbox.already-empty";
    public static final String INBOX_GONE = "inbox.gone";
    public static final String INBOX_CLEARED_ONE = "inbox.cleared-one";
    public static final String INBOX_CLEARED_MANY = "inbox.cleared-many";
    public static final String INBOX_HEADER = "inbox.header";
    public static final String INBOX_USAGE = "inbox.usage";
    public static final String INBOX_DELETED = "inbox.deleted";
    public static final String INBOX_LIST_FIRST = "inbox.list-first";
    public static final String INBOX_NO_ENTRY = "inbox.no-entry";
    public static final String INBOX_ROW_HOVER = "inbox.row-hover";
    public static final String INBOX_FOOTER = "inbox.footer";
    public static final String INBOX_FOOTER_PREVIOUS = "inbox.footer-previous";
    public static final String INBOX_FOOTER_NEXT = "inbox.footer-next";
    public static final String INBOX_FOOTER_PREVIOUS_INERT = "inbox.footer-previous-inert";
    public static final String INBOX_FOOTER_NEXT_INERT = "inbox.footer-next-inert";
    public static final String INBOX_READ_TITLE = "inbox.read-title";
    public static final String INBOX_READ_BODY = "inbox.read-body";
    public static final String INBOX_ROW_TITLE_ONLY_UNREAD = "inbox.row.title-only-unread";
    public static final String INBOX_ROW_TITLE_ONLY_READ = "inbox.row.title-only-read";

    // link
    public static final String LINK_NONE_AVAILABLE = "link.none-available";
    public static final String LINK_HEADER = "link.header";
    public static final String LINK_ENTRY = "link.entry";
    public static final String LINK_UNAVAILABLE = "link.unavailable";

    // preferences
    public static final String PREFERENCES_SAVED = "preferences.saved";
    public static final String PREFERENCES_SAVE_FAILED = "preferences.save-failed";
    public static final String PREFERENCES_DISCARDED = "preferences.discarded";
    public static final String PREFERENCES_MUTED = "preferences.muted";
    public static final String PREFERENCES_UNMUTED = "preferences.unmuted";
    public static final String PREFERENCES_MUTE_FAILED = "preferences.mute-failed";
    public static final String PREFERENCES_UNMUTE_FAILED = "preferences.unmute-failed";
    public static final String PREFERENCES_SESSION_DISCARDED = "preferences.session-discarded";

    // test
    public static final String TEST_SENT = "test.sent";
    public static final String TEST_SEPARATOR = "test.separator";
    public static final String TEST_MUTED = "test.muted";
    public static final String TEST_SILENCED = "test.silenced";
    public static final String TEST_NO_MEDIA = "test.no-media";
    public static final String TEST_NO_SINK = "test.no-sink";
    public static final String TEST_FAILED = "test.failed";

}
