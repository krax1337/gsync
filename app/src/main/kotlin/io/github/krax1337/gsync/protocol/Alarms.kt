package io.github.krax1337.gsync.protocol

data class Alarm(val hour: Int, val minute: Int, val enabled: Boolean)

/**
 * The five alarms plus the hourly time signal.
 *
 * On the wire the first alarm lives in [Feature.ALARM] and the other four in [Feature.ALARMS];
 * each entry is `flags 40 hour minute`. The hourly signal is a flag on the first entry.
 */
data class AlarmSet(val alarms: List<Alarm>, val hourlyChime: Boolean) {
    init {
        require(alarms.size == COUNT) { "The watch has $COUNT alarms, got ${alarms.size}" }
    }

    fun firstFrame(): ByteArray = frameOf(Feature.ALARM) + entry(alarms[0], hourlyChime)

    fun restFrame(): ByteArray = alarms.drop(1).fold(frameOf(Feature.ALARMS)) { frame, alarm -> frame + entry(alarm, false) }

    companion object {
        const val COUNT = 5
        private const val ENABLED = 0x40
        private const val CHIME = 0x80
        private const val ENTRY_SIZE = 4

        fun decode(first: ByteArray, rest: ByteArray): AlarmSet {
            first.requireFrame(Feature.ALARM, 1 + ENTRY_SIZE)
            rest.requireFrame(Feature.ALARMS, 1 + ENTRY_SIZE * (COUNT - 1))
            val entries = listOf(first.copyOfRange(1, 1 + ENTRY_SIZE)) +
                (0 until COUNT - 1).map { rest.copyOfRange(1 + it * ENTRY_SIZE, 1 + (it + 1) * ENTRY_SIZE) }
            return AlarmSet(
                alarms = entries.map { Alarm(hour = it.u(2), minute = it.u(3), enabled = it.u(0) and ENABLED != 0) },
                hourlyChime = first.u(1) and CHIME != 0,
            )
        }

        private fun entry(alarm: Alarm, chime: Boolean): ByteArray =
            frameOf(
                (if (alarm.enabled) ENABLED else 0) or (if (chime) CHIME else 0),
                0x40,
                alarm.hour,
                alarm.minute,
            )
    }
}
