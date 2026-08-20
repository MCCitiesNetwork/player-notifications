package io.github.md5sha256.playernotifications.essentials.convert;

/**
 * The outcome of one conversion run, or of one preview.
 *
 * <p>Every mail read from EssentialsX lands in exactly one of these buckets, so {@link #total()} is the
 * number of mails considered — an operator comparing it against {@code /essentials:mail} can see at a
 * glance whether the sweep saw everything.
 *
 * @param imported       mails written into first-party mail (or, for a preview, that would have been)
 * @param skippedExpired mails EssentialsX would no longer have shown
 * @param skippedBlank   mails with no message text
 * @param failed         mails whose enqueue threw; logged individually
 */
public record ConversionReport(int imported, int skippedExpired, int skippedBlank, int failed) {

    /** An empty run. */
    public static final ConversionReport EMPTY = new ConversionReport(0, 0, 0, 0);

    /** The number of mails considered. */
    public int total() {
        return this.imported + this.skippedExpired + this.skippedBlank + this.failed;
    }
}
