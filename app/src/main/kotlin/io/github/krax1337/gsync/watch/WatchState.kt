package io.github.krax1337.gsync.watch

import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchSettings
import kotlinx.coroutines.flow.Flow

data class Watch(val address: String, val name: String)

enum class LinkState {
    BLUETOOTH_OFF,

    /** Listening for the watch; it connects on its own schedule or when a button is pressed. */
    WAITING,
    CONNECTING,

    /** Connected, exchanging data. */
    SYNCING,

    /** Connected and idle: edits are written immediately. */
    CONNECTED,
}

enum class ClockSource { NTP, PHONE }

data class SyncRecord(
    /** Instant the watch was set to, epoch ms. */
    val atMs: Long,
    val trigger: Trigger,
    val source: ClockSource,
    /** Round trip of the NTP exchange the time came from; 0 for [ClockSource.PHONE]. */
    val ntpRoundTripMs: Int,
    /** Difference between network time and the phone clock at sync time; 0 for [ClockSource.PHONE]. */
    val phoneClockErrorMs: Long,
    val zone: String,
)

/** True time on this phone right now is `System.currentTimeMillis() + offsetMs`. */
data class ReferenceClock(val offsetMs: Long = 0, val source: ClockSource = ClockSource.PHONE)

/**
 * Everything the UI shows. Alarms, timer, settings and auto sync are the values last read from the
 * watch, or the user's edits while [pendingWrites] is set; they are null until the first connection.
 */
data class WatchState(
    /** Null while no watch is paired. */
    val watch: Watch? = null,
    val link: LinkState = LinkState.WAITING,
    /** What the watch should be showing; refreshed from the network whenever the app is opened. */
    val clock: ReferenceClock = ReferenceClock(),
    val batteryPercent: Int? = null,
    val temperatureC: Int? = null,
    val lastSync: SyncRecord? = null,
    val lastError: String? = null,
    val alarms: AlarmSet? = null,
    /** Start value of the watch's countdown timer, in seconds (1 s to 24 h). */
    val timerSeconds: Int? = null,
    val settings: WatchSettings? = null,
    val autoSync: AutoSync? = null,
    /** Edits made while disconnected, applied at the next connection. */
    val pendingWrites: Boolean = false,
    /** Added to the time sent to the watch, to calibrate out a constant error. */
    val fineAdjustMs: Int = 0,
    /** Whether holding the watch's lower-right button for five seconds rings this phone. */
    val phoneFinderEnabled: Boolean = true,
    /** The phone is ringing right now because the watch asked for it. */
    val finderRinging: Boolean = false,
)

/** What the UI can ask for. Edits are persisted first and reach the watch at the next opportunity. */
interface WatchActions {
    /** Nearby Casio watches that are advertising right now, for pairing. */
    fun discover(): Flow<Watch>

    fun pair(watch: Watch)

    fun unpair()

    fun setAlarms(alarms: AlarmSet)

    fun setTimer(seconds: Int)

    fun setSettings(settings: WatchSettings)

    fun setAutoSync(autoSync: AutoSync)

    fun setFineAdjust(ms: Int)

    fun setPhoneFinderEnabled(enabled: Boolean)

    /** Silences the phone finder tone. */
    fun stopFinder()

    /** Sets the time again; only does something while [LinkState.CONNECTED]. */
    fun syncNow()

    fun disconnect()
}
