package io.github.krax1337.gsync.protocol

import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Time setting on the GW-BX5600.
 *
 * Unlike older modules, this watch keeps its clock tables in three large blocks on a second pair
 * of characteristics. It only takes a new time after all three have been read and written back:
 *
 * 1. time slots: read with `05`, written back with `02`
 * 2. zone settings: read with `03`, written back with `06` plus one location record per time slot
 * 3. city names: read with `06`, written back unchanged
 *
 * The time itself then goes out as an ordinary [Feature.TIME] frame.
 */
object ClockBlocks {
    val SLOT_REQUEST: ByteArray = frameOf(0x05, 0x1D, 0x00, 0x1D, 0x00, 0x24, 0x00, 0x24, 0x01, 0x24, 0x02)
    const val SLOT_SIZE = 101

    val ZONE_REQUEST: ByteArray = frameOf(0x03, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00)
    const val ZONE_SIZE = 28

    // Six world-time cities, stored as two interleaved runs of three.
    val CITY_REQUEST: ByteArray = frameOf(0x06, 0x1F, 0, 0x1F, 6, 0x1F, 1, 0x1F, 7, 0x1F, 2, 0x1F, 8)
    const val CITY_SIZE = 133

    private const val SLOT_WRITE = 0x02
    private const val ZONE_WRITE = 0x06
    private const val TIME_SLOT = 0x24
    private const val CITY_NAME = 0x1F
    private const val CITY_RECORD = 22
    private const val SLOTS = 3

    fun slotWriteBack(block: ByteArray): ByteArray = block.requireBlock(0x05, SLOT_SIZE).withHeader(SLOT_WRITE)

    /** The zone block with the home location in slot 0; the other two slots are cleared. */
    fun zoneWriteBack(block: ByteArray, home: HomeLocation): ByteArray =
        (0 until SLOTS).fold(block.requireBlock(0x03, ZONE_SIZE).withHeader(ZONE_WRITE)) { out, slot ->
            out + if (slot == 0) locationRecord(slot, home) else locationRecord(slot, HomeLocation(0.0, 0.0, false))
        }

    fun cityWriteBack(block: ByteArray): ByteArray = block.requireBlock(0x06, CITY_SIZE)

    /**
     * The city names in the city block by index: 0 is the home city, 1 and 2 are the world time
     * cities.
     *
     * The layout is inferred from the sizes, not documented: six 22-byte records, each a classic
     * `1F index name` frame, with or without a two-byte length in front. A record that does not
     * look like that is left out, so the worst a wrong guess does is show no city.
     */
    fun cityNames(block: ByteArray): Map<Int, String> {
        if (block.size != CITY_SIZE) return emptyMap()
        return (1 until CITY_SIZE step CITY_RECORD).mapNotNull { start ->
            val marker = (start..start + 2).firstOrNull { block.u(it) == CITY_NAME } ?: return@mapNotNull null
            val text = String(block, marker + 2, start + CITY_RECORD - marker - 2, Charsets.US_ASCII)
            val name = text.takeWhile { it in ' '..'~' }.trim()
            if (name.isEmpty()) null else block.u(marker + 1) to name
        }.toMap()
    }

    /** `14 00 24 slot 01`, latitude and longitude as big-endian doubles, then the DST flag. */
    private fun locationRecord(slot: Int, location: HomeLocation): ByteArray =
        ByteBuffer.allocate(22)
            .put(0x14).put(0x00)
            .put(TIME_SLOT.toByte()).put(slot.toByte()).put(0x01)
            .putDouble(location.latitude).putDouble(location.longitude)
            .put(if (location.inDst) 1 else 0)
            .array()

    private fun ByteArray.requireBlock(header: Int, size: Int): ByteArray {
        if (this.size < size || u(0) != header) {
            throw ProtocolException("Expected block %02X of %d bytes, got %d bytes starting %s".format(header, size, this.size, take(4).toByteArray().toHex()))
        }
        return this
    }

    private fun ByteArray.withHeader(header: Int): ByteArray = copyOf().also { it[0] = header.toByte() }
}

/**
 * Where the watch believes home is: the coordinates of the Casio world-time city that keeps the
 * same clock as the phone's time zone, and whether daylight saving time is on right now.
 */
data class HomeLocation(val latitude: Double, val longitude: Double, val inDst: Boolean) {
    companion object {
        /** Zones of the cities built into the watch, in firmware order. */
        private val CASIO_CITIES = listOf(
            "UTC-12", "Pacific/Marquesas", "Pacific/Pago_Pago", "Pacific/Honolulu", "America/Anchorage",
            "America/Los_Angeles", "America/Denver", "America/Chicago", "America/New_York", "America/Halifax",
            "America/St_Johns", "America/Sao_Paulo", "America/Noronha", "Atlantic/Cape_Verde", "UTC",
            "Europe/London", "Europe/Paris", "Europe/Athens", "Asia/Riyadh", "Asia/Jerusalem",
            "Asia/Tehran", "Asia/Dubai", "Asia/Kabul", "Asia/Karachi", "Asia/Kolkata",
            "Asia/Kathmandu", "Asia/Dhaka", "Asia/Yangon", "Asia/Bangkok", "Asia/Hong_Kong",
            "Asia/Pyongyang", "Australia/Eucla", "Asia/Tokyo", "Australia/Adelaide", "Australia/Sydney",
            "Australia/Lord_Howe", "Pacific/Noumea", "Pacific/Auckland", "Pacific/Chatham", "Pacific/Tongatapu",
            "Pacific/Kiritimati", "Africa/Casablanca", "Asia/Beirut", "Pacific/Norfolk", "Pacific/Easter",
            "America/Havana", "America/Santiago", "America/Asuncion", "Atlantic/Azores",
        )

        fun of(zone: ZoneId, at: Instant): HomeLocation {
            val rules = zone.rules
            val city = CASIO_CITIES.firstOrNull { it == zone.id }
                ?: CASIO_CITIES.firstOrNull { id ->
                    val other = ZoneId.of(id).rules
                    other.getStandardOffset(at) == rules.getStandardOffset(at) &&
                        other.getDaylightSavings(at) == rules.getDaylightSavings(at) &&
                        other.transitionRules == rules.transitionRules
                }
                ?: zone.id
            // A zone nobody listed still gets the right longitude band for its offset.
            val (latitude, longitude) = ZoneCoordinates[city]
                ?: (0.0 to (rules.getStandardOffset(at).totalSeconds / 3600.0 * 15.0).coerceIn(-180.0, 180.0))
            return HomeLocation(latitude, longitude, rules.isDaylightSavings(at))
        }
    }
}

/** Wall-clock time for [Feature.TIME]: year (LE), month, day, hour, minute, second, weekday, 1/256 s. */
fun timeFrame(time: LocalDateTime): ByteArray =
    frameOf(
        Feature.TIME,
        time.year and 0xFF,
        time.year ushr 8,
        time.monthValue,
        time.dayOfMonth,
        time.hour,
        time.minute,
        time.second,
        time.dayOfWeek.value,
        (time.nano.toLong() * 256 / 1_000_000_000).toInt(),
        1,
    )
