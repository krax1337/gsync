package io.github.krax1337.gsync.protocol

/**
 * The activity record of a watch that counts steps, and the exchange that fetches it.
 *
 * The record does not travel as a feature frame. [START] is written to the watch's data request
 * characteristic; the watch answers there with the same two bytes and the length of the record,
 * then sends the record in pieces on a characteristic of its own. Ported from izivkov/GShockAPI
 * 2.0.0 (`StepCounterIO.kt`), whose history quotes one capture, taken from an ABL-100WE:
 *
 *     write   00 11 00 00 00
 *     answer  00 11 90 01 00 00 00    a record of 400 bytes follows
 *     pieces  26 07 01 18 40 01 ...
 *
 * The 0x11 is a category of this channel and has nothing to do with [Feature.AUTO_SYNC].
 *
 * What is relied on in the record:
 * - bytes 0 to 5 are the watch's date and time in BCD: year, month, day, hour, minute, second
 *   (the capture reads 1 July 2026, 18:40:01);
 * - bytes 374 to 377 are the steps of the current day, least significant byte first, and
 *   `FE FF FF FF` in their place means the watch has no count.
 *
 * The hourly and daily history, the distances and the rest of the record are not read.
 *
 * GShockAPI only accepts a record whose first byte is 0x26 and calls that a marker, yet reads the
 * same byte as the year. Both can hold in 2026 alone, so the six bytes are checked as a date and
 * time here, which lets a record of any other year through as well.
 */
object ActivityRecord {
    /**
     * Command 0, start a transfer, of category 0x11. Its counterpart `04 11 00 00 00` ends the
     * transfer and is left out on purpose: see `WatchSession.readSteps`.
     */
    val START: ByteArray = frameOf(0x00, 0x11, 0x00, 0x00, 0x00)

    private const val STEPS_AT = 374

    /** Year, month, day, hour, minute and second, each as far as it can go. */
    private val DATE_TIME = listOf(0..99, 1..12, 1..31, 0..23, 0..59, 0..59)

    /**
     * The length of the record the watch announces in [answer] to [request]: command and category
     * as they were sent, then the length in three bytes, least significant first. Null if [answer]
     * is something else.
     */
    fun announcedSize(request: ByteArray, answer: ByteArray): Int? {
        if (request.size < 2 || answer.size < 5 || answer[0] != request[0] || answer[1] != request[1]) return null
        return answer.u(2) or (answer.u(3) shl 8) or (answer.u(4) shl 16)
    }

    /**
     * The steps of the current day in a whole [record]. Null if the watch has no count, and if
     * [record] is too short or does not start with a date and time.
     */
    fun todaySteps(record: ByteArray): Int? {
        if (record.size < STEPS_AT + 4 || DATE_TIME.indices.any { bcd(record.u(it)) !in DATE_TIME[it] }) return null
        val steps = (0..3).fold(0L) { sum, byte -> sum or (record.u(STEPS_AT + byte).toLong() shl 8 * byte) }
        // "No count" is FFFFFFFE; neither that nor anything else above this is a number of steps.
        return steps.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** The two decimal digits of [byte], or -1 if a half of it is not a digit. */
    private fun bcd(byte: Int): Int = if (byte shr 4 <= 9 && byte and 0x0F <= 9) (byte shr 4) * 10 + (byte and 0x0F) else -1
}
