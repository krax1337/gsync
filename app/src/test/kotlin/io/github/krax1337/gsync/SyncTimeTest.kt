package io.github.krax1337.gsync

import io.github.krax1337.gsync.protocol.ClockBlocks
import io.github.krax1337.gsync.protocol.Feature
import io.github.krax1337.gsync.protocol.WatchProfiles
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTimeTest {
    private val zone = ZoneId.of("Asia/Almaty")

    // A quarter second past a whole second.
    private val start = Instant.parse("2026-11-21T07:30:00.250Z").toEpochMilli()

    /** A watch that hands out its clock blocks and acknowledges writes after [roundTripMs]. */
    private class FakeWatch(private val scope: TestScope, private val roundTripMs: Long) : WatchLink {
        /** Everything written, in order: virtual time, whether it was a block, the bytes. */
        val writes = ArrayList<Triple<Long, Boolean, ByteArray>>()
        val blockRequests = ArrayList<String>()

        override suspend fun request(vararg request: Int): ByteArray = error("Unexpected request ${request.toList()}")

        override suspend fun write(frame: ByteArray) {
            writes += Triple(scope.currentTime, false, frame)
            delay(roundTripMs)
        }

        override suspend fun requestBlock(request: ByteArray, size: Int): ByteArray {
            blockRequests += request.toHex()
            delay(roundTripMs)
            // Recognisable filler after the header the watch answers with.
            return ByteArray(size) { if (it == 0) request[0] else (it % 251).toByte() }
        }

        override suspend fun writeBlock(block: ByteArray) {
            writes += Triple(scope.currentTime, true, block)
            delay(roundTripMs)
        }

        override suspend fun requestRecord(request: ByteArray): ByteArray = error("Unexpected record request ${request.toHex()}")
    }

    private fun TestScope.session(watch: FakeWatch) = WatchSession(watch, WatchProfiles.of("CASIO GW-BX5600")) { currentTime }

    @Test
    fun `time reaches the watch on a whole second`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        val time = session(watch).syncTime(zone) { start + currentTime }.time
        val setTo = time.setToMs

        assertEquals(0, setTo % 1000)
        val (sentAt, isBlock, frame) = watch.writes.last()
        assertEquals(false, isBlock)
        assertEquals(timeFrame(LocalDateTime.ofInstant(Instant.ofEpochMilli(setTo), zone)).toHex(), frame.toHex())
        // Handed to the radio a quarter round trip early, at the first boundary after the blocks.
        assertEquals(setTo - 10, start + sentAt)
        assertTrue(setTo - (start + sentAt) < 1000)
        // Six table exchanges took 240 ms; the link was then idle until 10 ms before the second.
        assertEquals(listOf(10L, 500L, 40L), listOf(time.leadMs, time.idleMs, time.ackMs))
        assertEquals(false, time.late)
    }

    @Test
    fun `the three clock blocks are read and written back before the time`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        session(watch).syncTime(zone) { start + currentTime }

        assertEquals(
            listOf("051D001D00240024012402", "031E001E001E00", "061F001F061F011F071F021F08"),
            watch.blockRequests,
        )
        val (slots, zones, cities, time) = watch.writes.map { it.third }
        assertEquals(listOf(true, true, true, false), watch.writes.map { it.second })

        // Slots: same 101 bytes, header 05 -> 02.
        assertEquals(ClockBlocks.SLOT_SIZE, slots.size)
        assertEquals(0x02, slots.u(0))
        assertEquals((1 until 101).map { (it % 251).toByte() }, slots.drop(1))

        // Zones: header 03 -> 06, the 27 bytes read, then one 22-byte location record per slot.
        assertEquals(ClockBlocks.ZONE_SIZE + 3 * 22, zones.size)
        assertEquals(0x06, zones.u(0))
        assertEquals((1 until 28).map { (it % 251).toByte() }, zones.slice(1 until 28))

        // Cities: echoed unchanged.
        assertEquals(ClockBlocks.CITY_SIZE, cities.size)
        assertEquals(0x06, cities.u(0))

        assertEquals(Feature.TIME, time.u(0))
    }

    @Test
    fun `home slot carries the coordinates of the Casio city with the same clock`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        session(watch).syncTime(zone) { start + currentTime }

        val records = watch.writes[1].third.drop(ClockBlocks.ZONE_SIZE).chunked(22).map { ByteBuffer.wrap(it.toByteArray()) }
        fun header(record: ByteBuffer) = ByteArray(5).also { record.get(0, it) }.toHex()

        // Almaty is UTC+5 without DST: the watch's city for that clock is Karachi.
        assertEquals("1400240001", header(records[0]))
        assertEquals(24.8607, records[0].getDouble(5), 0.0)
        assertEquals(67.0011, records[0].getDouble(13), 0.0)
        assertEquals(0, records[0].get(21).toInt())

        // The world-time slots are cleared.
        assertEquals("1400240101", header(records[1]))
        assertEquals("1400240201", header(records[2]))
        assertEquals(0.0, records[1].getDouble(5), 0.0)
        assertEquals(0.0, records[2].getDouble(13), 0.0)
    }

    @Test
    fun `a write that keeps slipping still sets the exact time`() = runTest {
        val watch = FakeWatch(this, roundTripMs = 40)
        var slip = 0L
        // Once the blocks are written the clock jumps 370 ms every time the session looks at it:
        // no attempt can land within tolerance of its boundary.
        val now = {
            if (watch.writes.size >= 3) slip += 370
            start + currentTime + slip
        }
        val time = session(watch).syncTime(zone, now).time
        val setTo = time.setToMs

        val frame = watch.writes.last().third
        assertTrue(setTo % 1000 != 0L)
        assertTrue(time.late)
        assertEquals(timeFrame(LocalDateTime.ofInstant(Instant.ofEpochMilli(setTo), zone)).toHex(), frame.toHex())
    }
}
