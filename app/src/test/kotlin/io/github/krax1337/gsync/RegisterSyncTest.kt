package io.github.krax1337.gsync

import io.github.krax1337.gsync.protocol.Feature
import io.github.krax1337.gsync.protocol.WatchProfiles
import io.github.krax1337.gsync.protocol.frameOf
import io.github.krax1337.gsync.protocol.timeFrame
import io.github.krax1337.gsync.protocol.toHex
import io.github.krax1337.gsync.protocol.u
import io.github.krax1337.gsync.watch.WatchLink
import io.github.krax1337.gsync.watch.WatchSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterSyncTest {
    private val zone = ZoneId.of("Asia/Almaty")

    // A quarter second past a whole second.
    private val start = Instant.parse("2026-11-21T07:30:00.250Z").toEpochMilli()

    /**
     * A watch that keeps its clocks in registers. It answers each request with a frame that starts
     * with the request, and takes [roundTripMs] for every answer and every acknowledged write.
     */
    private class FakeWatch(
        private val scope: TestScope,
        private val roundTripMs: Long,
        /** The 18 name bytes of the city registers that have a name, by clock. */
        private val cities: Map<Int, ByteArray> = emptyMap(),
    ) : WatchLink {
        /** Every exchange in order: `read` and the request, or `write` and the frame. */
        val log = ArrayList<String>()

        /** What the watch answered, by request. */
        val answers = LinkedHashMap<String, String>()

        /** Virtual time at which the last write was handed over. */
        var lastWriteAt = 0L

        override suspend fun request(vararg request: Int): ByteArray {
            val asked = frameOf(*request)
            log += "read ${asked.toHex()}"
            delay(roundTripMs)
            // Recognisable contents, different for every register.
            val contents = cities[asked.u(1)]?.takeIf { asked.u(0) == Feature.CITY_NAME }
                ?: ByteArray(5) { (asked.u(0) * 7 + asked.u(1) * 3 + it).toByte() }
            return (asked + contents).also { answers[asked.toHex()] = it.toHex() }
        }

        override suspend fun write(frame: ByteArray) {
            log += "write ${frame.toHex()}"
            lastWriteAt = scope.currentTime
            delay(roundTripMs)
        }

        override suspend fun requestBlock(request: ByteArray, size: Int): ByteArray = error("A register watch has no clock blocks")

        override suspend fun writeBlock(block: ByteArray): Unit = error("A register watch has no clock blocks")

        override suspend fun requestRecord(request: ByteArray): ByteArray = error("Unexpected record request ${request.toHex()}")
    }

    private fun TestScope.session(watch: FakeWatch, name: String) = WatchSession(watch, WatchProfiles.of(name)) { currentTime }

    private fun name(text: String, pad: Int) = ByteArray(18) { if (it < text.length) text[it].code.toByte() else pad.toByte() }

    /** What a sync of a watch with these [registers] looks like up to the time: each read, then written back as it came. */
    private fun FakeWatch.echoed(vararg registers: String) = registers.flatMap { listOf("read $it", "write ${answers.getValue(it)}") }

    @Test
    fun `every register is read and written back unchanged before the time`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        val sync = session(watch, "CASIO GW-B5600").syncTime(zone) { start + currentTime }

        // Six clocks: three summer time registers, six zones, six cities.
        val registers = listOf("1D00", "1D02", "1D04") + (0..5).map { "1E0$it" } + (0..5).map { "1F0$it" }
        val time = timeFrame(LocalDateTime.ofInstant(Instant.ofEpochMilli(sync.time.setToMs), zone)).toHex()
        assertEquals(watch.echoed(*registers.toTypedArray()) + "write $time", watch.log)
    }

    @Test
    fun `what the watch sent is kept for a bug report under short labels`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        val sync = session(watch, "CASIO GW-B5600").syncTime(zone) { start + currentTime }

        assertEquals(
            listOf("dst0", "dst2", "dst4") + (0..5).map { "zone$it" } + (0..5).map { "city$it" },
            sync.read.map { it.first },
        )
        assertEquals(watch.answers.values.toList(), sync.read.map { it.second.toHex() })
    }

    @Test
    fun `time reaches a register watch on a whole second`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        val setTo = session(watch, "CASIO GW-B5600").syncTime(zone) { start + currentTime }.time.setToMs

        // Fifteen registers at 80 ms each end 1.45 s past the minute: the next whole second is the second one.
        assertEquals(Instant.parse("2026-11-21T07:30:02Z").toEpochMilli(), setTo)
        assertEquals("write " + timeFrame(LocalDateTime.ofInstant(Instant.ofEpochMilli(setTo), zone)).toHex(), watch.log.last())
        // Handed to the radio a quarter round trip early.
        assertEquals(setTo - 10, start + watch.lastWriteAt)
    }

    @Test
    fun `a watch with two clocks has one summer time register and two zones and cities`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        session(watch, "CASIO GA-B2100").syncTime(zone) { start + currentTime }

        assertEquals(watch.echoed("1D00", "1E00", "1E01", "1F00", "1F01"), watch.log.dropLast(1))
    }

    @Test
    fun `a watch that keeps home time records gets those back instead of city names`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        val sync = session(watch, "CASIO GBD-200").syncTime(zone) { start + currentTime }

        assertEquals(watch.echoed("1D00", "1E00", "1E01", "2400", "2401"), watch.log.dropLast(1))
        assertEquals(listOf("dst0", "zone0", "zone1", "slot0", "slot1"), sync.read.map { it.first })
        // Their layout is not known, so no city is shown.
        assertEquals(emptyMap<Int, String>(), sync.cityNames)
    }

    @Test
    fun `a watch without city registers is not asked for any`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        val sync = session(watch, "CASIO ABL-100WE").syncTime(zone) { start + currentTime }

        assertEquals(watch.echoed("1D00", "1E00", "1E01"), watch.log.dropLast(1))
        assertEquals("write 09", watch.log.last().take(8))
        assertEquals(emptyMap<Int, String>(), sync.cityNames)
    }

    @Test
    fun `city names are read whatever pads them, home first`() = runTest {
        val cities = mapOf(
            0 to name("TOKYO", 0x00),
            1 to name("NEW YORK", 0x20),
            2 to name("", 0xFF),
            3 to name("F.DE NORONHA", 0xFF),
            4 to name("", 0x00),
        )
        val watch = FakeWatch(this, roundTripMs = 40, cities)
        val sync = session(watch, "CASIO GMW-B5000").syncTime(zone) { start + currentTime }

        assertEquals(mapOf(0 to "TOKYO", 1 to "NEW YORK", 3 to "F.DE NORONHA"), sync.cityNames)
    }
}
