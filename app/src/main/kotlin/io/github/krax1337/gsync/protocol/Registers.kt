package io.github.krax1337.gsync.protocol

/**
 * Time setting on the watches that keep their clocks in registers: the GW-B5600, the GA-B2100 and
 * most other Bluetooth models older than the GW-BX5600.
 *
 * Such a watch only takes a new time after its clock registers have been read and written back,
 * each as an ordinary feature frame:
 *
 * 1. summer time: `1D clock clock+1 …`, one register for every two clocks
 * 2. zone: `1E clock …`, one per clock
 * 3. city: `1F clock` and 18 characters of name, or `24 clock …`, one per clock; some models have
 *    neither
 *
 * Clock 0 is home time, the others are world time. Every register goes back the way it came, so
 * the home city stays the one chosen on the watch. GShockAPI rewrites clock 0 to follow the phone's
 * time zone instead; that is not done here as long as nobody can try it on a watch.
 */
object ClockRegisters {
    /** The registers [profile] has, in the order they are read: a short label and the request for each. */
    fun requests(profile: WatchProfile): List<Pair<String, IntArray>> {
        val clocks = 0 until profile.clocks
        val city: List<Pair<String, IntArray>> = when (profile.cityRegister) {
            CityRegister.NONE -> emptyList()
            CityRegister.NAMES -> clocks.map { "city$it" to intArrayOf(Feature.CITY_NAME, it) }
            CityRegister.HOME_TIME -> clocks.map { "slot$it" to intArrayOf(Feature.HOME_TIME, it) }
        }
        return clocks.step(2).map { "dst$it" to intArrayOf(Feature.DST_STATE, it) } +
            clocks.map { "zone$it" to intArrayOf(Feature.ZONE, it) } +
            city
    }

    /**
     * The city names among [frames] by clock, 0 being the home city. A register that holds no
     * readable name is left out.
     */
    fun cityNames(frames: List<ByteArray>): Map<Int, String> =
        frames.filter { it.size > 2 && it.u(0) == Feature.CITY_NAME }.mapNotNull { frame ->
            val name = String(frame, 2, frame.size - 2, Charsets.US_ASCII).takeWhile { it in ' '..'~' }.trim()
            if (name.isEmpty()) null else frame.u(1) to name
        }.toMap()
}
