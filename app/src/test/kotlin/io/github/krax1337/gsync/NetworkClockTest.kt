package io.github.krax1337.gsync

import io.github.krax1337.gsync.time.NetworkClock
import io.github.krax1337.gsync.time.NtpSample
import io.github.krax1337.gsync.time.Sntp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class NetworkClockTest {
    private val unixEpochInNtpSeconds = 2_208_988_800L

    /** Server answer: received the request at [receiveMs], sent the answer at [transmitMs] (Unix ms). */
    private fun answer(receiveMs: Long, transmitMs: Long, firstByte: Int = 0x24, stratum: Int = 2): ByteArray {
        val packet = ByteBuffer.allocate(48)
        packet.put(0, firstByte.toByte()).put(1, stratum.toByte())
        fun putTimestamp(offset: Int, ms: Long) {
            packet.putInt(offset, (ms / 1000 + unixEpochInNtpSeconds).toInt())
            packet.putInt(offset + 4, ((ms % 1000 shl 32) / 1000).toInt())
        }
        putTimestamp(32, receiveMs)
        putTimestamp(40, transmitMs)
        return packet.array()
    }

    @Test
    fun `network time is the server time plus half the network round trip`() {
        // Request left at monotonic 5000, answer came back at 5340; the server held it for 250 ms.
        val sample = Sntp.parse(answer(receiveMs = 1_800_000_000_250, transmitMs = 1_800_000_000_500), sentMs = 5000, receivedMs = 5340)!!
        assertEquals(NtpSample(epochMs = 1_800_000_000_545, monotonicMs = 5340, roundTripMs = 90), sample)
    }

    @Test
    fun `answers from unsynchronised servers are rejected`() {
        assertNull(Sntp.parse(answer(1_800_000_000_000, 1_800_000_000_000, firstByte = 0xE4), 0, 10)) // leap = alarm
        assertNull(Sntp.parse(answer(1_800_000_000_000, 1_800_000_000_000, stratum = 0), 0, 10)) // kiss of death
        assertNull(Sntp.parse(ByteArray(12), 0, 10))
    }

    @Test
    fun `clock follows the best sample and falls back to the phone when it is stale`() = runTest {
        var monotonic = 1_000L
        val samples = mapOf(
            "time.cloudflare.com" to NtpSample(epochMs = 2_000_000, monotonicMs = 1_000, roundTripMs = 90),
            "time.google.com" to NtpSample(epochMs = 2_000_040, monotonicMs = 1_000, roundTripMs = 12),
        )
        val clock = NetworkClock(monotonicMs = { monotonic }, phoneMs = { 7 }, sample = { samples[it] })

        assertEquals(NetworkClock.Reading(7, null), clock.now())

        assertEquals(true, clock.refresh())
        monotonic += 500
        assertEquals(2_000_540, clock.now().epochMs)
        assertEquals(12L, clock.now().sample?.roundTripMs)

        monotonic += 31 * 60 * 1000
        assertEquals(NetworkClock.Reading(7, null), clock.now())
    }
}
