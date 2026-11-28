package io.github.krax1337.gsync

import io.github.krax1337.gsync.protocol.ActivityRecord
import io.github.krax1337.gsync.protocol.Alarm
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.ClockBlocks
import io.github.krax1337.gsync.protocol.Condition
import io.github.krax1337.gsync.protocol.HomeLocation
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.protocol.ProtocolException
import io.github.krax1337.gsync.protocol.Timer
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchCities
import io.github.krax1337.gsync.protocol.WatchProfiles
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.protocol.frameOf
import io.github.krax1337.gsync.protocol.hexToFrame
import io.github.krax1337.gsync.protocol.timeFrame
import io.github.krax1337.gsync.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ProtocolTest {
    // Frames below are captures quoted in izivkov/GShockAPI and its notes.

    @Test
    fun `time frame matches a captured write`() {
        val captured = LocalDateTime.of(2024, 5, 17, 12, 20, 15, 812_500_000)
        assertEquals("09E80705110C140F05D001", timeFrame(captured).toHex())
    }

    @Test
    fun `trigger is read from byte 8`() {
        fun trigger(code: String) = Trigger.decode("101762073885CD7F${code}030FFFFFFFFF24000000".hexToFrame())
        assertEquals(Trigger.CONNECT, trigger("01"))
        assertEquals(Trigger.CONNECT, trigger("00"))
        assertEquals(Trigger.FIND_PHONE, trigger("02"))
        assertEquals(Trigger.AUTO_SYNC, trigger("03"))
        assertEquals(Trigger.TIME_BUTTON, trigger("04"))
    }

    @Test
    fun `home is the Casio city itself when the phone zone is one`() {
        val july = Instant.parse("2026-07-01T00:00:00Z")
        assertEquals(HomeLocation(40.7128, -74.0060, inDst = true), HomeLocation.of(ZoneId.of("America/New_York"), july))
        assertEquals(false, HomeLocation.of(ZoneId.of("America/New_York"), Instant.parse("2026-01-01T00:00:00Z")).inDst)
    }

    @Test
    fun `home is the Casio city that keeps the same clock otherwise`() {
        val winter = Instant.parse("2026-01-01T00:00:00Z")
        // Berlin follows the same rules as Paris, Almaty the same clock as Karachi.
        assertEquals(HomeLocation.of(ZoneId.of("Europe/Paris"), winter), HomeLocation.of(ZoneId.of("Europe/Berlin"), winter))
        assertEquals(HomeLocation.of(ZoneId.of("Asia/Karachi"), winter), HomeLocation.of(ZoneId.of("Asia/Almaty"), winter))
    }

    @Test
    fun `a zone with no matching Casio city keeps its own coordinates`() {
        // UTC-6 without DST matches no built-in city (Chicago observes DST).
        val home = HomeLocation.of(ZoneId.of("America/Guatemala"), Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(14.6349, home.latitude, 0.01)
        assertEquals(-90.5069, home.longitude, 0.01)
    }

    @Test
    fun `clock blocks of the wrong kind or size are refused`() {
        assertThrows(ProtocolException::class.java) { ClockBlocks.slotWriteBack(ByteArray(100).also { it[0] = 0x05 }) }
        assertThrows(ProtocolException::class.java) { ClockBlocks.slotWriteBack(ByteArray(101).also { it[0] = 0x03 }) }
        assertThrows(ProtocolException::class.java) { ClockBlocks.cityWriteBack(ByteArray(133)) }
    }

    @Test
    fun `the cities of the three clocks are read from the city block, without their codes`() {
        fun record(index: Int, name: String, pad: Int) =
            frameOf(0x14, 0x00, 0x1F, index) + ByteArray(18) { if (it < name.length) name[it].code.toByte() else pad.toByte() }
        val block = frameOf(0x06) +
            record(0, "KARACHI", 0x00) + record(6, "KHI", 0x00) +
            record(1, "(UTC)", 0x20) + record(7, "UTC", 0x20) +
            record(2, "F. DE NORONHA", 0xFF) + record(8, "FEN", 0xFF)

        assertEquals(mapOf(0 to "KARACHI", 1 to "UTC", 2 to "F. DE NORONHA"), ClockBlocks.cityNames(block))
        // A block that is not laid out as expected shows no city instead of a wrong one.
        assertEquals(emptyMap<Int, String>(), ClockBlocks.cityNames(ByteArray(133) { 0x41 }))
        assertEquals(emptyMap<Int, String>(), ClockBlocks.cityNames(block.copyOf(100)))
    }

    @Test
    fun `alarms round trip through both frames`() {
        val first = "15C0400700".hexToFrame() // 07:00, enabled, hourly signal on
        val rest = "16" + "00400C1E" + "4040170F" + "00400000" + "00400000"
        val alarms = AlarmSet.decode(first, rest.hexToFrame())
        assertEquals(
            AlarmSet(
                listOf(Alarm(7, 0, true), Alarm(12, 30, false), Alarm(23, 15, true), Alarm(0, 0, false), Alarm(0, 0, false)),
                hourlyChime = true,
            ),
            alarms,
        )
        assertEquals(first.toHex(), alarms.firstFrame().toHex())
        assertEquals(rest, alarms.restFrame().toHex())
    }

    @Test
    fun `a watch with any number of alarms is stored and read back as its two frames`() {
        for (count in 1..6) {
            val alarms = AlarmSet(List(count) { Alarm(hour = it + 6, minute = it * 5, enabled = it % 2 == 0) }, hourlyChime = count % 2 == 0)
            assertEquals(alarms, AlarmSet.decode(alarms.firstFrame(), alarms.restFrame()))
            assertEquals(4 * (count - 1), alarms.restFrame().size - 1)
        }
    }

    @Test
    fun `timer is hours minutes seconds and keeps the rest of its frame`() {
        val fromWatch = "1800 0A00 AABBCC".replace(" ", "").hexToFrame()
        assertEquals(600, Timer.decode(fromWatch))
        assertEquals("18011E2DAABBCC", Timer.applyTo(fromWatch, 1 * 3600 + 30 * 60 + 45).toHex())
        assertEquals("18180000AABBCC", Timer.applyTo(fromWatch, Timer.MAX_SECONDS).toHex())
        assertThrows(IllegalArgumentException::class.java) { Timer.applyTo(fromWatch, 0) }
    }

    @Test
    fun `settings decode from flags that are mostly inverted`() {
        val settings = WatchSettings.decode("1305000001050000200000000000000000".hexToFrame())
        assertEquals(
            WatchSettings(
                use24Hour = true,
                buttonTone = true,
                autoLight = false,
                powerSaving = true,
                longLight = false,
                dayFirst = true,
                language = Language.RUSSIAN,
                classicFont = true,
            ),
            settings,
        )
    }

    @Test
    fun `writing settings keeps the bits this app does not know`() {
        val fromWatch = "13E8FE7700000000CF0000000000000000".hexToFrame()
        val edited = WatchSettings(
            use24Hour = true,
            buttonTone = false,
            autoLight = true,
            powerSaving = false,
            longLight = true,
            dayFirst = false,
            language = Language.GERMAN,
            classicFont = true,
        )
        val written = edited.applyTo(fromWatch)
        assertEquals("13FBFF7700030000EF0000000000000000", written.toHex())
        assertEquals(edited, WatchSettings.decode(written))
        assertEquals("CF", edited.copy(classicFont = false).applyTo(written).toHex().substring(16, 18))
    }

    @Test
    fun `a short settings frame has no font byte to touch`() {
        val short = "130500000105".hexToFrame()
        val settings = WatchSettings.decode(short)
        assertEquals(false, settings.classicFont)
        assertEquals(short.toHex(), settings.copy(classicFont = true).applyTo(short).toHex())
    }

    @Test
    fun `a setting the model lacks keeps the bits the watch sent`() {
        val fromWatch = "13E8FE7700000000CF0000000000000000".hexToFrame()
        // Switching auto light off and the digits to classic would set bit 2 of byte 1 and bit 5 of byte 8.
        val edited = WatchSettings(
            use24Hour = true,
            buttonTone = false,
            autoLight = false,
            powerSaving = false,
            longLight = true,
            dayFirst = false,
            language = Language.GERMAN,
            classicFont = true,
        )
        assertEquals("13FFFF7700030000EF0000000000000000", edited.applyTo(fromWatch).toHex())
        // The GA-B2100 has neither auto light nor a choice of digits.
        val fields = WatchProfiles.of("CASIO GA-B2100").settings
        assertEquals("13FBFF7700030000CF0000000000000000", edited.applyTo(fromWatch, fields).toHex())
        assertEquals(fromWatch.toHex(), edited.applyTo(fromWatch, emptySet()).toHex())
    }

    @Test
    fun `auto sync lives in bytes 12 and 13`() {
        val on = "110F0F0F06005000040001000037D2".hexToFrame()
        assertEquals(AutoSync(enabled = true, minute = 55), AutoSync.decode(on))
        assertEquals("110F0F0F0600500004000100801ED2", AutoSync(enabled = false, minute = 30).applyTo(on).toHex())

        // Only bit 7 of byte 12 is the switch: what else the watch keeps there stays.
        val withHour = "110F0F0F06005000040001000637D2".hexToFrame()
        assertEquals(AutoSync(enabled = true, minute = 55), AutoSync.decode(withHour))
        assertEquals("110F0F0F0600500004000100861ED2", AutoSync(enabled = false, minute = 30).applyTo(withHour).toHex())
    }

    @Test
    fun `battery reading is scaled to percent`() {
        val watch = WatchProfiles.of("CASIO GW-BX5600")
        assertEquals(Condition(batteryPercent = 100, temperatureC = 27), Condition.decode("28181B00".hexToFrame(), watch))
        assertEquals(Condition(batteryPercent = 50, temperatureC = -3), Condition.decode("2813FD00".hexToFrame(), watch))
        assertEquals(0, Condition.decode("280A1B00".hexToFrame(), watch).batteryPercent)
    }

    @Test
    fun `the same battery reading means more on a module with a lower scale`() {
        val watch = WatchProfiles.of("CASIO GW-B5600")
        assertEquals(100, Condition.decode("28131B00".hexToFrame(), watch).batteryPercent)
        assertEquals(50, Condition.decode("280E1B00".hexToFrame(), watch).batteryPercent)
        assertEquals(0, Condition.decode("28091B00".hexToFrame(), watch).batteryPercent)
    }

    @Test
    fun `a value the model does not report is not made up`() {
        val reading = "28131B00".hexToFrame()
        // The GBD-200 has no thermometer, the ABL-100WE reports neither.
        assertEquals(Condition(batteryPercent = 100, temperatureC = null), Condition.decode(reading, WatchProfiles.of("CASIO GBD-200")))
        assertEquals(Condition(batteryPercent = null, temperatureC = null), Condition.decode(reading, WatchProfiles.of("CASIO ABL-100WE")))
    }

    @Test
    fun `the watch city for a phone zone is the one on the same standard time`() {
        fun cities(zone: String) = WatchCities.matching(ZoneId.of(zone), Instant.parse("2026-07-01T00:00:00Z")).map { it.name }
        assertEquals(listOf("KARACHI"), cities("Asia/Almaty"))
        // Summer time does not move the match: the watch stores standard time and switches itself.
        assertEquals(listOf("MADRID", "PARIS", "ROME", "BERLIN", "STOCKHOLM"), cities("Europe/Berlin"))
        // UTC is a clock, not a place to call home.
        assertEquals(listOf("LISBON", "LONDON"), cities("Europe/London"))
        assertEquals(listOf("KATHMANDU"), cities("Asia/Kathmandu"))
        assertEquals(emptyList<String>(), cities("UTC+02:30"))
    }

    @Test
    fun `the city table has the 55 cities of the operation guide plus UTC, by offset`() {
        assertEquals(56, WatchCities.ALL.size)
        assertEquals(WatchCities.ALL.sortedBy { it.offsetMinutes }, WatchCities.ALL)
    }

    /** An activity record as [ActivityRecord] describes it, of the length the watch in GShockAPI's capture announces. */
    private fun activityRecord(dateTime: String, steps: String): ByteArray {
        // Everything that is not read holds FE FF, the mark of a slot without a value.
        val record = ByteArray(400) { if (it % 2 == 0) 0xFE.toByte() else 0xFF.toByte() }
        dateTime.hexToFrame().copyInto(record) // Bytes 0 to 5.
        steps.hexToFrame().copyInto(record, 374)
        return record
    }

    @Test
    fun `the length of an activity record is read from the answer to its request`() {
        // The exchange GShockAPI quotes from an ABL-100WE.
        assertEquals("0011000000", ActivityRecord.START.toHex())
        assertEquals(400, ActivityRecord.announcedSize(ActivityRecord.START, "00119001000000".hexToFrame()))
        // An answer of another category, and one too short to hold a length.
        assertEquals(null, ActivityRecord.announcedSize(ActivityRecord.START, "002E9001000000".hexToFrame()))
        assertEquals(null, ActivityRecord.announcedSize(ActivityRecord.START, "00119001".hexToFrame()))
    }

    @Test
    fun `the steps of the day are read from bytes 374 to 377 of the activity record`() {
        // 28 November 2026, 09:15:42, and 6482 steps (0x1952), least significant byte first.
        val record = activityRecord("261128091542", "52190000")
        assertEquals(6482, ActivityRecord.todaySteps(record))
        // Nothing behind the count is needed.
        assertEquals(6482, ActivityRecord.todaySteps(record.copyOf(378)))
        // The first byte is the year and not a mark: a record of another year reads the same.
        assertEquals(123_456, ActivityRecord.todaySteps(activityRecord("270101000000", "40E20100")))
        assertEquals(0, ActivityRecord.todaySteps(activityRecord("261128091542", "00000000")))
    }

    @Test
    fun `an activity record that is cut short or starts with no date gives no steps`() {
        val record = activityRecord("261128091542", "52190000")
        assertEquals(null, ActivityRecord.todaySteps(record.copyOf(377)))
        assertEquals(null, ActivityRecord.todaySteps(ByteArray(0)))
        // A month 13, an hour 24, and a byte that is no pair of digits.
        assertEquals(null, ActivityRecord.todaySteps(activityRecord("261328091542", "52190000")))
        assertEquals(null, ActivityRecord.todaySteps(activityRecord("261128241542", "52190000")))
        assertEquals(null, ActivityRecord.todaySteps(activityRecord("FF1128091542", "52190000")))
    }

    @Test
    fun `a watch that has no count for the day gives no steps`() {
        assertEquals(null, ActivityRecord.todaySteps(activityRecord("261128091542", "FEFFFFFF")))
    }
}
