package io.github.krax1337.gsync.time

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Wall clock backed by NTP: the last network sample, carried forward with the monotonic clock.
 * Falls back to the phone clock while no recent sample exists.
 */
class NetworkClock(
    private val monotonicMs: () -> Long,
    private val phoneMs: () -> Long,
    private val sample: suspend (host: String) -> NtpSample? = { Sntp.query(it, QUERY_TIMEOUT_MS, monotonicMs) },
) {
    data class Reading(val epochMs: Long, val sample: NtpSample?)

    @Volatile
    private var latest: NtpSample? = null

    /** Asks all servers at once and keeps the answer with the shortest round trip. */
    suspend fun refresh(): Boolean {
        val best = coroutineScope { SERVERS.map { async { sample(it) } }.awaitAll() }
            .filterNotNull()
            .minByOrNull { it.roundTripMs }
        if (best != null) latest = best
        return best != null
    }

    fun now(): Reading {
        val monotonic = monotonicMs()
        val sample = latest?.takeIf { monotonic - it.monotonicMs in 0..MAX_SAMPLE_AGE_MS }
        return if (sample != null) Reading(sample.epochMs + (monotonic - sample.monotonicMs), sample) else Reading(phoneMs(), null)
    }

    private companion object {
        val SERVERS = listOf("time.cloudflare.com", "time.google.com", "pool.ntp.org")
        const val QUERY_TIMEOUT_MS = 2_000

        // The phone's oscillator drifts tens of ppm: half an hour keeps the error under ~50 ms.
        const val MAX_SAMPLE_AGE_MS = 30 * 60 * 1000L
    }
}
