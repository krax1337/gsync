package io.github.krax1337.gsync.watch

import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AppInfo
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.ClockBlocks
import io.github.krax1337.gsync.protocol.Condition
import io.github.krax1337.gsync.protocol.Feature
import io.github.krax1337.gsync.protocol.HomeLocation
import io.github.krax1337.gsync.protocol.Timer
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.protocol.timeFrame
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** One connected watch. Operations are strictly sequential: one in flight at a time. */
interface WatchLink {
    /** Asks for the feature identified by [request] and returns the frame the watch answers with. */
    suspend fun request(vararg request: Int): ByteArray

    /** Stores [frame] on the watch; returns once the watch has acknowledged it. */
    suspend fun write(frame: ByteArray)

    /** Asks for a clock block; the watch answers in pieces, returned joined once [size] bytes are in. */
    suspend fun requestBlock(request: ByteArray, size: Int): ByteArray

    /** Stores a clock block; returns once the watch has acknowledged it. */
    suspend fun writeBlock(block: ByteArray)
}

/** The watch operations of this app, on top of a [WatchLink]. */
class WatchSession(private val link: WatchLink, private val monotonicMs: () -> Long) {
    private val writeRoundTripsMs = ArrayList<Long>()

    /** First exchange of every connection. */
    suspend fun hello(): Trigger {
        if (link.request(Feature.APP_INFO).contentEquals(AppInfo.BLANK)) write(AppInfo.DEFAULT)
        return Trigger.decode(link.request(Feature.TRIGGER))
    }

    /**
     * Sets the time and returns the instant (epoch ms) the watch was set to.
     *
     * The watch only takes a new time after its three clock blocks have been written back (see
     * [ClockBlocks]). The time itself is written so that it reaches the watch on a whole second:
     * correct whether or not the firmware honours the sub-second byte.
     */
    suspend fun syncTime(zoneId: ZoneId, nowMs: () -> Long): Long {
        val home = HomeLocation.of(zoneId, Instant.ofEpochMilli(nowMs()))
        val slots = link.requestBlock(ClockBlocks.SLOT_REQUEST, ClockBlocks.SLOT_SIZE)
        writeBlock(ClockBlocks.slotWriteBack(slots))
        val zones = link.requestBlock(ClockBlocks.ZONE_REQUEST, ClockBlocks.ZONE_SIZE)
        writeBlock(ClockBlocks.zoneWriteBack(zones, home))
        val cities = link.requestBlock(ClockBlocks.CITY_REQUEST, ClockBlocks.CITY_SIZE)
        writeBlock(ClockBlocks.cityWriteBack(cities))

        val lead = transmitLeadMs()
        repeat(BOUNDARY_ATTEMPTS) {
            val target = (nowMs() + lead) / 1000 * 1000 + 1000
            val sendAt = target - lead
            delay(sendAt - nowMs())
            if (nowMs() - sendAt <= LATE_TOLERANCE_MS) {
                writeTime(target, zoneId)
                return target
            }
        }
        // Timers keep firing late (device busy or dozing): send the exact instant instead.
        return (nowMs() + lead).also { writeTime(it, zoneId) }
    }

    suspend fun readAlarms(): AlarmSet = AlarmSet.decode(link.request(Feature.ALARM), link.request(Feature.ALARMS))

    suspend fun writeAlarms(alarms: AlarmSet) {
        write(alarms.firstFrame())
        write(alarms.restFrame())
    }

    suspend fun readTimer(): Int = Timer.decode(link.request(Feature.TIMER))

    suspend fun writeTimer(seconds: Int) = write(Timer.applyTo(link.request(Feature.TIMER), seconds))

    suspend fun readSettings(): WatchSettings = WatchSettings.decode(link.request(Feature.SETTINGS))

    suspend fun writeSettings(settings: WatchSettings) = write(settings.applyTo(link.request(Feature.SETTINGS)))

    suspend fun readAutoSync(): AutoSync = AutoSync.decode(link.request(Feature.AUTO_SYNC))

    suspend fun writeAutoSync(autoSync: AutoSync) = write(autoSync.applyTo(link.request(Feature.AUTO_SYNC)))

    suspend fun readCondition(): Condition = Condition.decode(link.request(Feature.CONDITION))

    private suspend fun writeTime(epochMs: Long, zoneId: ZoneId) =
        link.write(timeFrame(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zoneId)))

    private suspend fun write(frame: ByteArray) = timed { link.write(frame) }

    private suspend fun writeBlock(block: ByteArray) = timed { link.writeBlock(block) }

    /** Runs one acknowledged write and remembers how long the watch took to acknowledge it. */
    private suspend fun timed(write: suspend () -> Unit) {
        val start = monotonicMs()
        write()
        writeRoundTripsMs += monotonicMs() - start
    }

    /**
     * How long before the target instant the time frame has to be handed to the radio.
     *
     * An acknowledged write takes about two connection intervals when issued back to back. A write
     * issued at an arbitrary moment waits half an interval on average before it goes out.
     */
    private fun transmitLeadMs(): Long =
        if (writeRoundTripsMs.isEmpty()) 0 else writeRoundTripsMs.sorted()[writeRoundTripsMs.size / 2] / 4

    private companion object {
        const val BOUNDARY_ATTEMPTS = 3
        const val LATE_TOLERANCE_MS = 30
    }
}
