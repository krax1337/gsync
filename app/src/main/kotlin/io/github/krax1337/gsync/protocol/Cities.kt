package io.github.krax1337.gsync.protocol

import java.time.Instant
import java.time.ZoneId

/** A city built into the watch, as it appears when choosing a Home City or World Time city. */
data class WatchCity(
    /** Three-letter code the watch shows before the name; empty for Baker Island and UTC. */
    val code: String,
    val name: String,
    /** Standard-time offset from UTC, in minutes. */
    val offsetMinutes: Int,
)

/** The watch's city table (operation guide for module 3578), ordered by offset. */
object WatchCities {
    val ALL: List<WatchCity> = listOf(
        WatchCity("", "BAKER ISLAND", -720),
        WatchCity("PPG", "PAGO PAGO", -660),
        WatchCity("HNL", "HONOLULU", -600),
        WatchCity("NHV", "MARQUESAS ISLANDS", -570),
        WatchCity("ANC", "ANCHORAGE", -540),
        WatchCity("YVR", "VANCOUVER", -480),
        WatchCity("LAX", "LOS ANGELES", -480),
        WatchCity("YEA", "EDMONTON", -420),
        WatchCity("DEN", "DENVER", -420),
        WatchCity("MEX", "MEXICO CITY", -360),
        WatchCity("CHI", "CHICAGO", -360),
        WatchCity("NYC", "NEW YORK", -300),
        WatchCity("YHZ", "HALIFAX", -240),
        WatchCity("YYT", "ST. JOHN'S", -210),
        WatchCity("BUE", "BUENOS AIRES", -180),
        WatchCity("RIO", "RIO DE JANEIRO", -180),
        WatchCity("FEN", "F. DE NORONHA", -120),
        WatchCity("RAI", "PRAIA", -60),
        WatchCity("", "UTC", 0),
        WatchCity("LIS", "LISBON", 0),
        WatchCity("LON", "LONDON", 0),
        WatchCity("MAD", "MADRID", 60),
        WatchCity("PAR", "PARIS", 60),
        WatchCity("ROM", "ROME", 60),
        WatchCity("BER", "BERLIN", 60),
        WatchCity("STO", "STOCKHOLM", 60),
        WatchCity("ATH", "ATHENS", 120),
        WatchCity("CAI", "CAIRO", 120),
        WatchCity("JRS", "JERUSALEM", 120),
        WatchCity("MOW", "MOSCOW", 180),
        WatchCity("JED", "JEDDAH", 180),
        WatchCity("THR", "TEHRAN", 210),
        WatchCity("DXB", "DUBAI", 240),
        WatchCity("KBL", "KABUL", 270),
        WatchCity("KHI", "KARACHI", 300),
        WatchCity("DEL", "DELHI", 330),
        WatchCity("KTM", "KATHMANDU", 345),
        WatchCity("DAC", "DHAKA", 360),
        WatchCity("RGN", "YANGON", 390),
        WatchCity("BKK", "BANGKOK", 420),
        WatchCity("SIN", "SINGAPORE", 480),
        WatchCity("HKG", "HONG KONG", 480),
        WatchCity("BJS", "BEIJING", 480),
        WatchCity("TPE", "TAIPEI", 480),
        WatchCity("EUC", "EUCLA", 525),
        WatchCity("SEL", "SEOUL", 540),
        WatchCity("TYO", "TOKYO", 540),
        WatchCity("ADL", "ADELAIDE", 570),
        WatchCity("GUM", "GUAM", 600),
        WatchCity("SYD", "SYDNEY", 600),
        WatchCity("LDH", "LORD HOWE ISLAND", 630),
        WatchCity("NOU", "NOUMEA", 660),
        WatchCity("WLG", "WELLINGTON", 720),
        WatchCity("CHT", "CHATHAM ISLAND", 765),
        WatchCity("TBU", "NUKUALOFA", 780),
        WatchCity("CXI", "KIRITIMATI", 840),
    )

    /**
     * The cities to choose on the watch for a phone in [zone]: those on the same standard time.
     * Empty when the watch has no city on that offset.
     */
    fun matching(zone: ZoneId, at: Instant): List<WatchCity> {
        val offsetMinutes = zone.rules.getStandardOffset(at).totalSeconds / 60
        return ALL.filter { it.offsetMinutes == offsetMinutes && it.name != "UTC" }
    }
}
