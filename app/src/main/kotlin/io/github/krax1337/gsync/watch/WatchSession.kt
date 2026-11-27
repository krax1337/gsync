package io.github.krax1337.gsync.watch

import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AppInfo
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.ClockBlocks
import io.github.krax1337.gsync.protocol.ClockRegisters
import io.github.krax1337.gsync.protocol.Condition
import io.github.krax1337.gsync.protocol.Feature
import io.github.krax1337.gsync.protocol.HomeLocation
import io.github.krax1337.gsync.protocol.ProtocolException
import io.github.krax1337.gsync.protocol.Support
import io.github.krax1337.gsync.protocol.TimeProtocol
import io.github.krax1337.gsync.protocol.Timer
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchProfile
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.protocol.frameOf
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

/** How the write that set the time went. The durations are for diagnostics. */
class TimeWrite(
    /** The instant (epoch ms) the watch was set to. */
    val setToMs: Long,
    /** How long before that instant the frame was handed to the radio. */
    val leadMs: Long,
    /** How long the link sat idle between the last table write and this one. */
    val idleMs: Long,
    /** How long the watch took to acknowledge the write. */
    val ackMs: Long,
    /** No attempt met its whole second, so the exact instant was sent instead. */
    val late: Boolean,
    /** How long the watch took to acknowledge each write before this one, in order. */
    val roundTripsMs: List<Long>,
)

/** What a time sync did. */
class TimeSync(
    val time: TimeWrite,
    /** What the watch sent on the way, each with a short label, for diagnostics. */
    val read: List<Pair<String, ByteArray>>,
    /** City names stored on the watch by index, 0 being home. Empty if they could not be read. */
    val cityNames: Map<Int, String>,
)

/**
 * The watch operations of this app, on top of a [WatchLink].
 *
 * What is exchanged follows [profile]: a feature the model lacks never reaches the watch. Reading
 * one returns null, writing one is refused.
 */
class WatchSession(private val link: WatchLink, private val profile: WatchProfile, private val monotonicMs: () -> Long) {
    private val writeRoundTripsMs = ArrayList<Long>()

    init {
        // Nothing is known about such a watch, so nothing may be sent to it.
        if (profile.support == Support.UNSUPPORTED) throw ProtocolException("G-Sync does not support the ${profile.model}")
    }

    /** First exchange of every connection. */
    suspend fun hello(): Trigger {
        if (link.request(Feature.APP_INFO).contentEquals(AppInfo.BLANK)) write(AppInfo.DEFAULT)
        return Trigger.decode(link.request(Feature.TRIGGER))
    }

    /**
     * Sets the time.
     *
     * The watch only takes a new time after its clock tables have been written back: three blocks
     * (see [ClockBlocks]) or a row of registers (see [ClockRegisters]), depending on the model.
     * The time itself is written so that it reaches the watch on a whole second: correct whether
     * or not the firmware honours the sub-second byte.
     */
    suspend fun syncTime(zoneId: ZoneId, nowMs: () -> Long): TimeSync =
        when (profile.timeProtocol) {
            TimeProtocol.CLOCK_BLOCKS -> {
                val home = HomeLocation.of(zoneId, Instant.ofEpochMilli(nowMs()))
                val slots = link.requestBlock(ClockBlocks.SLOT_REQUEST, ClockBlocks.SLOT_SIZE)
                writeBlock(ClockBlocks.slotWriteBack(slots))
                val zones = link.requestBlock(ClockBlocks.ZONE_REQUEST, ClockBlocks.ZONE_SIZE)
                writeBlock(ClockBlocks.zoneWriteBack(zones, home))
                val cities = link.requestBlock(ClockBlocks.CITY_REQUEST, ClockBlocks.CITY_SIZE)
                writeBlock(ClockBlocks.cityWriteBack(cities))
                val read = listOf("slots" to slots, "zones" to zones, "cities" to cities)
                TimeSync(writeTimeOnSecond(zoneId, nowMs), read, ClockBlocks.cityNames(cities))
            }
            TimeProtocol.REGISTERS -> {
                val read = ClockRegisters.requests(profile).map { (label, request) ->
                    val register = link.request(*request)
                    write(register)
                    label to register
                }
                TimeSync(writeTimeOnSecond(zoneId, nowMs), read, ClockRegisters.cityNames(read.map { it.second }))
            }
        }

    suspend fun readAlarms(): AlarmSet {
        val first = link.request(Feature.ALARM)
        val rest = if (profile.alarmCount > 1) link.request(Feature.ALARMS) else frameOf(Feature.ALARMS)
        return AlarmSet.decode(first, rest).forModel()
    }

    suspend fun writeAlarms(alarms: AlarmSet) {
        if (alarms.alarms.size != profile.alarmCount) {
            throw ProtocolException("The ${profile.model} has ${profile.alarmCount} alarms, not ${alarms.alarms.size}")
        }
        val set = alarms.forModel()
        write(set.firstFrame())
        if (profile.alarmCount > 1) write(set.restFrame())
    }

    /** Null on a model without a timer this app can set. */
    suspend fun readTimer(): Int? = if (profile.hasTimer) Timer.decode(link.request(Feature.TIMER)) else null

    suspend fun writeTimer(seconds: Int) {
        if (!profile.hasTimer) lacks("timer")
        write(Timer.applyTo(link.request(Feature.TIMER), seconds))
    }

    suspend fun readSettings(): WatchSettings = WatchSettings.decode(link.request(Feature.SETTINGS))

    suspend fun writeSettings(settings: WatchSettings) = write(settings.applyTo(link.request(Feature.SETTINGS), profile.settings))

    /** Null on a model without automatic time adjustment. */
    suspend fun readAutoSync(): AutoSync? = if (profile.hasAutoSync) AutoSync.decode(link.request(Feature.AUTO_SYNC)) else null

    suspend fun writeAutoSync(autoSync: AutoSync) {
        if (!profile.hasAutoSync) lacks("automatic time adjustment")
        write(autoSync.applyTo(link.request(Feature.AUTO_SYNC)))
    }

    /** Battery and temperature, as far as the model reports them. */
    suspend fun readCondition(): Condition =
        if (profile.hasBattery || profile.hasTemperature) {
            Condition.decode(link.request(Feature.CONDITION), profile)
        } else {
            Condition(batteryPercent = null, temperatureC = null)
        }

    /** A model without the hourly signal may use its flag for something else: it stays off. */
    private fun AlarmSet.forModel(): AlarmSet = if (profile.hasHourlySignal) this else copy(hourlyChime = false)

    private fun lacks(feature: String): Nothing = throw ProtocolException("The ${profile.model} has no $feature this app can set")

    /** Writes the time so that it lands on a whole second. */
    private suspend fun writeTimeOnSecond(zoneId: ZoneId, nowMs: () -> Long): TimeWrite {
        val lead = transmitLeadMs()
        val idleSince = monotonicMs()
        repeat(BOUNDARY_ATTEMPTS) {
            val target = (nowMs() + lead) / 1000 * 1000 + 1000
            val sendAt = target - lead
            delay(sendAt - nowMs())
            if (nowMs() - sendAt <= LATE_TOLERANCE_MS) return writeTime(target, zoneId, lead, idleSince, late = false)
        }
        // Timers keep firing late (device busy or dozing): send the exact instant instead.
        return writeTime(nowMs() + lead, zoneId, lead, idleSince, late = true)
    }

    private suspend fun writeTime(epochMs: Long, zoneId: ZoneId, leadMs: Long, idleSinceMs: Long, late: Boolean): TimeWrite {
        val sent = monotonicMs()
        link.write(timeFrame(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zoneId)))
        return TimeWrite(epochMs, leadMs, sent - idleSinceMs, monotonicMs() - sent, late, writeRoundTripsMs.toList())
    }

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
