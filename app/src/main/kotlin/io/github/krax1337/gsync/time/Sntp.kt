package io.github.krax1337.gsync.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/** Network time observed at one instant of the local monotonic clock. */
data class NtpSample(val epochMs: Long, val monotonicMs: Long, val roundTripMs: Long)

/** Minimal SNTP (RFC 4330) client: one request, one answer. */
object Sntp {
    private const val PACKET_SIZE = 48
    private const val PORT = 123
    private const val CLIENT_REQUEST = 0x23 // LI 0, version 4, mode 3 (client)
    private const val MODE_SERVER = 4
    private const val LEAP_UNSYNCHRONIZED = 3
    private const val TRANSMIT_OFFSET = 40
    private const val RECEIVE_OFFSET = 32
    private const val NTP_TO_UNIX_SECONDS = 2_208_988_800L

    fun request(): ByteArray = ByteArray(PACKET_SIZE).also { it[0] = CLIENT_REQUEST.toByte() }

    /**
     * Network time at the moment the answer arrived, or null if [response] is not a usable answer.
     * [sentMs] and [receivedMs] are monotonic clock readings around the exchange.
     */
    fun parse(response: ByteArray, sentMs: Long, receivedMs: Long): NtpSample? {
        if (response.size < PACKET_SIZE) return null
        val leap = (response[0].toInt() shr 6) and 0x3
        val mode = response[0].toInt() and 0x7
        val stratum = response[1].toInt() and 0xFF
        if (mode != MODE_SERVER || leap == LEAP_UNSYNCHRONIZED || stratum == 0) return null
        val serverReceive = timestampMs(response, RECEIVE_OFFSET)
        val serverTransmit = timestampMs(response, TRANSMIT_OFFSET)
        val roundTrip = (receivedMs - sentMs) - (serverTransmit - serverReceive)
        if (roundTrip < 0) return null
        return NtpSample(epochMs = serverTransmit + roundTrip / 2, monotonicMs = receivedMs, roundTripMs = roundTrip)
    }

    suspend fun query(host: String, timeoutMs: Int, monotonicMs: () -> Long): NtpSample? =
        withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val address = InetAddress.getByName(host)
                    val response = DatagramPacket(ByteArray(PACKET_SIZE), PACKET_SIZE)
                    val sent = monotonicMs()
                    socket.send(DatagramPacket(request(), PACKET_SIZE, address, PORT))
                    socket.receive(response)
                    parse(response.data, sent, monotonicMs())
                }
            }.getOrNull()
        }

    /** 64-bit NTP timestamp (seconds since 1900 + binary fraction) as Unix epoch milliseconds. */
    private fun timestampMs(packet: ByteArray, offset: Int): Long {
        val buffer = ByteBuffer.wrap(packet, offset, 8)
        val seconds = buffer.int.toLong() and 0xFFFFFFFFL
        val fraction = buffer.int.toLong() and 0xFFFFFFFFL
        return (seconds - NTP_TO_UNIX_SECONDS) * 1000 + (fraction * 1000 ushr 32)
    }
}
