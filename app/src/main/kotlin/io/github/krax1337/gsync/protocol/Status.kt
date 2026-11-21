package io.github.krax1337.gsync.protocol

/** Why the watch opened the connection, from byte 8 of the [Feature.TRIGGER] frame. */
enum class Trigger {
    /** Lower-left button held: the watch stays connected until either side hangs up. */
    CONNECT,

    /** Lower-right button pressed in timekeeping mode: one-shot time sync. */
    TIME_BUTTON,

    /** One of the four daily automatic adjustments, no button involved. */
    AUTO_SYNC,

    FIND_PHONE;

    val staysConnected: Boolean get() = this == CONNECT

    companion object {
        fun decode(frame: ByteArray): Trigger =
            when (frame.requireFrame(Feature.TRIGGER, 9).u(8)) {
                2 -> FIND_PHONE
                3 -> AUTO_SYNC
                4 -> TIME_BUTTON
                else -> CONNECT
            }
    }
}

/**
 * The watch keeps 11 bytes of app-owned data in [Feature.APP_INFO]. After a watch reset it reads
 * back blank, and the lower-right button stays disabled until an app stores something there.
 * [DEFAULT] is the value the official app writes.
 */
object AppInfo {
    val BLANK: ByteArray = "22FFFFFFFFFFFFFFFFFFFF00".hexToFrame()
    val DEFAULT: ByteArray = "223488F4E5D5AFC829E06D02".hexToFrame()
}

data class Condition(val batteryPercent: Int, val temperatureC: Int) {
    companion object {
        // Raw battery reading spans 14 (empty) to 24 (full) on this module.
        private const val BATTERY_EMPTY = 14
        private const val BATTERY_FULL = 24

        fun decode(frame: ByteArray): Condition {
            frame.requireFrame(Feature.CONDITION, 3)
            val percent = (frame.u(1) - BATTERY_EMPTY) * 100 / (BATTERY_FULL - BATTERY_EMPTY)
            return Condition(percent.coerceIn(0, 100), frame[2].toInt())
        }
    }
}

/** Start value of the countdown timer: `18 hours minutes seconds`, then bytes this app leaves alone. */
object Timer {
    const val MAX_SECONDS = 24 * 3600
    private const val MIN_SIZE = 4

    fun decode(frame: ByteArray): Int {
        frame.requireFrame(Feature.TIMER, MIN_SIZE)
        return frame.u(1) * 3600 + frame.u(2) * 60 + frame.u(3)
    }

    fun applyTo(frame: ByteArray, seconds: Int): ByteArray {
        require(seconds in 1..MAX_SECONDS) { "Timer must be between 1 second and 24 hours, got $seconds s" }
        return frame.requireFrame(Feature.TIMER, MIN_SIZE).copyOf().also {
            it[1] = (seconds / 3600).toByte()
            it[2] = (seconds % 3600 / 60).toByte()
            it[3] = (seconds % 60).toByte()
        }
    }
}
