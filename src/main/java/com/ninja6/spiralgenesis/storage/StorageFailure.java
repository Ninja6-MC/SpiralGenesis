package com.ninja6.spiralgenesis.storage;

/**
 * Why the stored records could not be read, and where the unreadable file was preserved.
 *
 * @param error      the parse or read error, one line, for an operator
 * @param brokenCopy the file name the unreadable {@code data.yml} was copied to, or
 *                   {@code null} if the copy could not be made
 */
public record StorageFailure(String error, String brokenCopy) {

    /** One sentence naming the preserved copy, or saying there is none. */
    public String copyNote() {
        return brokenCopy == null
                ? "It could not be copied aside, so take a copy of it before editing it."
                : "A copy of it was saved as " + brokenCopy + ".";
    }
}
