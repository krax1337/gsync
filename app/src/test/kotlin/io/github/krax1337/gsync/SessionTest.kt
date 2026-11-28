package io.github.krax1337.gsync

import io.github.krax1337.gsync.protocol.Alarm
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.Condition
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.protocol.ProtocolException
import io.github.krax1337.gsync.protocol.WatchProfile
import io.github.krax1337.gsync.protocol.WatchProfiles
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.protocol.hexToFrame
import io.github.krax1337.gsync.protocol.toHex
import io.github.krax1337.gsync.watch.WatchLink
import io.github.krax1337.gsync.watch.WatchSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** What is exchanged for alarms, timer, settings, condition and steps, model by model. */
class SessionTest {
    /** A watch holding one frame per feature. Asking for a feature it does not hold fails the test. */
    private class FakeWatch : WatchLink {
        private val frames = listOf(
            "15C0400700", // 07:00, enabled, hourly signal on
            "16" + "00400C1E" + "4040170F" + "00400000" + "00400000",
            "18000A00000000", // ten minutes
            "1304000000000000200000000000000000", // auto light off, classic digits
            "110F0F0F06005000040001000037D2", // on, 55 minutes past
            "28131B00", // battery reading 19, 27 °C
        ).associateBy { it.take(2) }

        /** The activity record the watch hands out. Asking a watch that has none fails the test. */
        var activityRecord: ByteArray? = null

        /** Every exchange in order: `read` and the feature, `write` and the frame, or `record` and the request. */
        val log = ArrayList<String>()

        override suspend fun request(vararg request: Int): ByteArray {
            val feature = "%02X".format(request.single())
            log += "read $feature"
            return frames.getValue(feature).hexToFrame()
        }

        override suspend fun write(frame: ByteArray) {
            log += "write ${frame.toHex()}"
        }

        override suspend fun requestBlock(request: ByteArray, size: Int): ByteArray = error("Unexpected block request")

        override suspend fun writeBlock(block: ByteArray): Unit = error("Unexpected block write")

        override suspend fun requestRecord(request: ByteArray): ByteArray {
            log += "record ${request.toHex()}"
            return activityRecord ?: error("Unexpected record request")
        }
    }

    private fun session(watch: FakeWatch, profile: WatchProfile) = WatchSession(watch, profile) { 0 }

    private fun session(watch: FakeWatch, name: String) = session(watch, WatchProfiles.of(name))

    /** The message the session refused with, or null if it did what was asked. */
    private suspend fun refusal(action: suspend () -> Unit): String? =
        try {
            action()
            null
        } catch (e: ProtocolException) {
            e.message
        }

    private val fiveAlarms = AlarmSet(
        listOf(Alarm(7, 0, true), Alarm(12, 30, false), Alarm(23, 15, true), Alarm(0, 0, false), Alarm(0, 0, false)),
        hourlyChime = true,
    )

    @Test
    fun `the GW-BX5600 is asked for everything it was asked for before`() = runTest {
        val watch = FakeWatch()
        val session = session(watch, "CASIO GW-BX5600")

        assertEquals(Condition(batteryPercent = 50, temperatureC = 27), session.readCondition())
        assertEquals(fiveAlarms, session.readAlarms())
        assertEquals(600, session.readTimer())
        assertEquals(true, session.readSettings().classicFont)
        assertEquals(AutoSync(enabled = true, minute = 55), session.readAutoSync())
        assertEquals(listOf("read 28", "read 15", "read 16", "read 18", "read 13", "read 11"), watch.log)
    }

    @Test
    fun `a watch whose timer the app cannot set is never asked about it`() = runTest {
        val watch = FakeWatch()
        val session = session(watch, "CASIO GBD-200")

        assertEquals(null, session.readTimer())
        assertNotNull(refusal { session.writeTimer(600) })
        assertEquals(emptyList<String>(), watch.log)
    }

    @Test
    fun `battery and temperature are only read from a watch that reports one of them`() = runTest {
        val square = FakeWatch()
        assertEquals(Condition(batteryPercent = 100, temperatureC = 27), session(square, "CASIO GW-B5600").readCondition())
        assertEquals(listOf("read 28"), square.log)

        val tracker = FakeWatch()
        assertEquals(Condition(batteryPercent = 100, temperatureC = null), session(tracker, "CASIO GBD-200").readCondition())

        val retro = FakeWatch()
        assertEquals(Condition(batteryPercent = null, temperatureC = null), session(retro, "CASIO ABL-100WE").readCondition())
        assertEquals(emptyList<String>(), retro.log)
    }

    @Test
    fun `a watch without a step counter is never asked for its steps`() = runTest {
        for (name in listOf("CASIO GW-BX5600", "CASIO GBD-200")) {
            val watch = FakeWatch()
            assertEquals(name, null, session(watch, name).readSteps())
            assertEquals(name, emptyList<String>(), watch.log)
        }
    }

    @Test
    fun `steps are read by starting a transfer and nothing is sent to end it`() = runTest {
        val watch = FakeWatch()
        // 28 November 2026, 09:15:42, and 6482 steps from byte 374 on.
        watch.activityRecord = ByteArray(400).also {
            "261128091542".hexToFrame().copyInto(it)
            "52190000".hexToFrame().copyInto(it, 374)
        }

        assertEquals(6482, session(watch, "CASIO ABL-100WE").readSteps())
        assertEquals(listOf("record 0011000000"), watch.log)
    }

    @Test
    fun `writing settings leaves alone what the model does not have`() = runTest {
        val watch = FakeWatch()
        val edited = WatchSettings(
            use24Hour = true,
            buttonTone = false,
            autoLight = true,
            powerSaving = false,
            longLight = true,
            dayFirst = true,
            language = Language.GERMAN,
            classicFont = false,
        )
        // The GA-B2100 has neither auto light nor a choice of digits: both stay as the watch sent them.
        session(watch, "CASIO GA-B2100").writeSettings(edited)

        assertEquals(listOf("read 13", "write 1317010001030000200000000000000000"), watch.log)
    }

    @Test
    fun `a watch with a single alarm has no second alarm frame`() = runTest {
        val watch = FakeWatch()
        val session = session(watch, WatchProfiles.of("CASIO GA-B2100").copy(alarmCount = 1))

        val alarms = session.readAlarms()
        assertEquals(AlarmSet(listOf(Alarm(7, 0, true)), hourlyChime = true), alarms)
        session.writeAlarms(alarms.copy(alarms = listOf(Alarm(6, 45, true))))
        assertEquals(listOf("read 15", "write 15C040062D"), watch.log)
    }

    @Test
    fun `alarms that do not fit the watch are not written`() = runTest {
        val watch = FakeWatch()
        val session = session(watch, WatchProfiles.of("CASIO GA-B2100").copy(alarmCount = 1))

        assertNotNull(refusal { session.writeAlarms(fiveAlarms) })
        assertEquals(emptyList<String>(), watch.log)
    }

    @Test
    fun `a watch without the hourly signal never has it switched on`() = runTest {
        val watch = FakeWatch()
        val session = session(watch, WatchProfiles.of("CASIO GA-B2100").copy(hasHourlySignal = false))

        assertEquals(false, session.readAlarms().hourlyChime)
        session.writeAlarms(fiveAlarms)
        assertEquals("write 1540400700", watch.log[2])
    }

    @Test
    fun `no session is opened on a watch the app does not support`() {
        val watch = FakeWatch()
        val refused = assertThrows(ProtocolException::class.java) { session(watch, "CASIO DW-H5600") }

        assertEquals("G-Sync does not support the DW-H5600", refused.message)
        assertEquals(emptyList<String>(), watch.log)
    }
}
