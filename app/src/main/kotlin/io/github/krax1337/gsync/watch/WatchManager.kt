package io.github.krax1337.gsync.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import io.github.krax1337.gsync.ble.GattLink
import io.github.krax1337.gsync.ble.LinkLostException
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.ProtocolException
import io.github.krax1337.gsync.protocol.Support
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchProfiles
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.protocol.toHex
import io.github.krax1337.gsync.sync.PhoneFinder
import io.github.krax1337.gsync.time.NetworkClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.ZoneId

/**
 * Owns the paired watches: listens for them, serves each connection, and keeps what the UI shows.
 *
 * A watch is a peripheral that advertises only when it wants to talk (four times a day, or on a
 * button press), and only for a short while. [run] keeps a filtered scan going and connects the
 * moment one of them shows up. One watch is served at a time.
 */
@SuppressLint("MissingPermission") // run() and discover() are only reached once Bluetooth permissions are granted.
@OptIn(ExperimentalCoroutinesApi::class)
class WatchManager(private val context: Context) : WatchActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = WatchStore(context)
    private val clock = NetworkClock(SystemClock::elapsedRealtime, System::currentTimeMillis)
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val finder = PhoneFinder(context)

    private val link = MutableStateFlow(Link())
    private val bluetoothOn = MutableStateFlow(adapter?.isEnabled == true)
    private val error = MutableStateFlow<Problem?>(null)
    private val reference = MutableStateFlow(ReferenceClock())
    private val uiVisible = MutableStateFlow(false)

    /** Set while a watch stays connected after its initial exchange. */
    @Volatile
    private var connected: Connected? = null
    private val exchange = Mutex()
    private var lastScanStartMs = -SCAN_SPACING_MS

    private class Connected(val address: String, val gatt: GattLink, val session: WatchSession)

    val state: StateFlow<WatchState> =
        combine(store.saved, combine(link, bluetoothOn, error, reference, finder.ringing, ::Live), ::toState)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                toState(
                    runBlocking { store.saved.first() },
                    Live(link.value, bluetoothOn.value, error.value, reference.value, finder.ringing.value),
                ),
            )

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                bluetoothOn.value = adapter?.isEnabled == true
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
        // The Watch tab shows the time the watch should have: make it network time.
        if (visible) scope.launch { refreshClock() }
    }

    /** Serves the paired watches until cancelled. Returns when no watch is paired. */
    suspend fun run() {
        val adapter = adapter ?: return
        store.saved.map { saved -> saved.watches.map { it.watch.address } }
            .distinctUntilChanged()
            .transformLatest { addresses -> if (addresses.isEmpty()) emit(Unit) else serve(adapter, addresses) }
            .first()
    }

    /** Completes without a result when Bluetooth is off or refuses to scan. */
    override fun discover(): Flow<Watch> = callbackFlow {
        val paired = state.value.watches.mapTo(HashSet()) { it.address }
        val scanner = adapter?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (name.startsWith("CASIO", ignoreCase = true) && result.device.address !in paired) {
                    trySend(Watch(result.device.address, name))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close()
            }
        }
        if (scanner == null) close() else scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner?.stopScan(callback) } }
    }

    override fun pair(watch: Watch) = edit { saved ->
        when {
            saved.watch(watch.address) != null -> saved.copy(selected = watch.address)
            WatchProfiles.of(watch.name).support == Support.UNSUPPORTED -> saved
            else -> saved.copy(watches = saved.watches + SavedWatch(watch), selected = watch.address)
        }
    }

    override fun unpair() = edit { saved ->
        val address = saved.current?.watch?.address
        saved.copy(watches = saved.watches.filter { it.watch.address != address }, selected = null)
    }

    override fun select(address: String) = edit { it.copy(selected = address) }

    override fun setAlarms(alarms: AlarmSet) = editCurrent { it.copy(alarms = alarms, alarmsPending = true) }

    override fun setTimer(seconds: Int) = editCurrent { it.copy(timerSeconds = seconds, timerPending = true) }

    override fun setSettings(settings: WatchSettings) = editCurrent { it.copy(settings = settings, settingsPending = true) }

    override fun setAutoSync(autoSync: AutoSync) = editCurrent { it.copy(autoSync = autoSync, autoSyncPending = true) }

    override fun setFineAdjust(ms: Int) = editCurrent { it.copy(fineAdjustMs = ms) }

    override fun setPhoneFinderEnabled(enabled: Boolean) {
        if (!enabled) finder.stop()
        edit { it.copy(phoneFinderEnabled = enabled) }
    }

    override fun stopFinder() = finder.stop()

    override fun syncNow() {
        whileConnected { peer ->
            refreshClock()
            syncTime(peer, Trigger.CONNECT)
        }
    }

    private suspend fun refreshClock() {
        clock.refresh()
        val reading = clock.now()
        reference.value = ReferenceClock(
            offsetMs = reading.epochMs - System.currentTimeMillis(),
            source = if (reading.sample != null) ClockSource.NTP else ClockSource.PHONE,
        )
    }

    override fun disconnect() {
        connected?.gatt?.disconnect()
    }

    /** Persists an edit, then writes it to the watch if that one is connected. */
    private fun edit(transform: (Saved) -> Saved) {
        scope.launch {
            store.update(transform)
            whileConnected { pushPending(it) }.join()
        }
    }

    /** An edit of the watch on screen. */
    private fun editCurrent(transform: (SavedWatch) -> SavedWatch) =
        edit { saved -> saved.with(saved.current?.watch?.address, transform) }

    private fun whileConnected(block: suspend (Connected) -> Unit) = scope.launch {
        exchange.withLock {
            val peer = connected ?: return@launch
            try {
                block(peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = Problem(peer.address, e.message)
            }
        }
    }

    private suspend fun serve(adapter: BluetoothAdapter, addresses: List<String>): Nothing {
        while (true) {
            var address: String? = null
            try {
                address = awaitAdvertisement(adapter, addresses)
                link.value = Link(address, LinkState.CONNECTING)
                connect(adapter.getRemoteDevice(address))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = Problem(address, e.message)
            }
        }
    }

    /** Returns the address of the first of [addresses] that advertises. */
    private suspend fun awaitAdvertisement(adapter: BluetoothAdapter, addresses: List<String>): String =
        combine(bluetoothOn, uiVisible, ::Pair)
            .mapLatest { (on, visible) ->
                if (!on) awaitCancellation()
                link.value = Link()
                // A filtered low-power scan keeps running with the screen off. While the app is
                // open, scan continuously so a button press connects at once.
                scanFor(adapter, addresses, if (visible) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            }
            .first()

    private suspend fun scanFor(adapter: BluetoothAdapter, addresses: List<String>, mode: Int): String {
        // Android silently ignores an app that starts more than five scans in 30 seconds.
        delay(lastScanStartMs + SCAN_SPACING_MS - SystemClock.elapsedRealtime())
        val scanner = adapter.bluetoothLeScanner ?: awaitCancellation()
        val found = CompletableDeferred<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device.address)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IOException("Bluetooth scan failed ($errorCode)"))
            }
        }
        lastScanStartMs = SystemClock.elapsedRealtime()
        scanner.startScan(
            addresses.map { ScanFilter.Builder().setDeviceAddress(it).build() },
            ScanSettings.Builder().setScanMode(mode).build(),
            callback,
        )
        try {
            return found.await()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private suspend fun connect(device: BluetoothDevice) {
        val ntp = scope.async { refreshClock() }
        // Keeps the CPU awake through the exchange: the time write is scheduled to the millisecond.
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gsync:exchange")
            .apply { acquire(EXCHANGE_WAKE_MS) }
        var gatt: GattLink? = null
        try {
            gatt = GattLink.open(context, device)
            val profile = WatchProfiles.of(store.saved.first().watch(device.address)?.watch?.name.orEmpty())
            val peer = Connected(device.address, gatt, WatchSession(gatt, profile, SystemClock::elapsedRealtime))
            link.value = Link(peer.address, LinkState.SYNCING)
            var ringingSinceMs: Long? = null
            val trigger = exchange.withLock {
                val trigger = peer.session.hello()
                error.value = null
                if (trigger == Trigger.FIND_PHONE && store.saved.first().phoneFinderEnabled) {
                    ring(peer)
                    if (finder.ringing.value) ringingSinceMs = SystemClock.elapsedRealtime()
                }
                // Edits first: the watch may hang up as soon as an automatic sync has its time.
                pushPending(peer)
                withTimeoutOrNull(NTP_WAIT_MS) { ntp.await() }
                syncTime(peer, trigger)
                try {
                    pull(peer)
                } catch (_: LinkLostException) {
                    // The time is set; whatever was not read is still current from last time.
                }
                trigger
            }
            val ringingSince = ringingSinceMs
            when {
                trigger.staysConnected -> {
                    connected = peer
                    link.value = Link(peer.address, LinkState.CONNECTED)
                    if (wakeLock.isHeld) wakeLock.release()
                    whileConnected { pushPending(it) } // Edits made during the exchange above.
                    gatt.awaitLost()
                }
                ringingSince != null -> {
                    // During the first 30 seconds any watch button stops the tone: the watch does
                    // that by dropping the connection. A drop right after the exchange, or when
                    // those 30 seconds run out, is the watch's own doing and leaves the tone on.
                    withTimeoutOrNull(FINDER_LINK_MS) { gatt.awaitLost() }
                    if (SystemClock.elapsedRealtime() - ringingSince in FINDER_BUTTON_WINDOW_MS) finder.stop()
                }
                else -> withTimeoutOrNull(HANG_UP_GRACE_MS) { gatt.awaitLost() }
            }
        } finally {
            connected = null
            gatt?.close()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun syncTime(peer: Connected, trigger: Trigger) {
        val adjust = store.saved.first().watch(peer.address)?.fineAdjustMs ?: 0
        val zone = ZoneId.systemDefault()
        val sync = peer.session.syncTime(zone) { clock.now().epochMs + adjust }
        val reading = clock.now()
        val record = SyncRecord(
            atMs = sync.setToMs - adjust,
            trigger = trigger,
            source = if (reading.sample != null) ClockSource.NTP else ClockSource.PHONE,
            ntpRoundTripMs = reading.sample?.roundTripMs?.toInt() ?: 0,
            phoneClockErrorMs = if (reading.sample != null) System.currentTimeMillis() - reading.epochMs else 0,
            zone = zone.id,
        )
        update(peer) { watch ->
            watch.copy(
                lastSync = record,
                homeCity = sync.cityNames[0],
                worldCities = sync.cityNames.filterKeys { it != 0 }.toSortedMap().values.toList(),
                syncData = sync.read.map { (label, bytes) -> "$label ${bytes.toHex()}" },
            )
        }
    }

    /**
     * Writes edits made since the last connection. An edit made meanwhile stays pending, and so
     * does one the watch refuses: it is reported and must not keep the time from being set.
     */
    private suspend fun pushPending(peer: Connected) {
        val saved = store.saved.first().watch(peer.address) ?: return
        val session = peer.session
        if (saved.alarmsPending && saved.alarms != null) optional(peer) {
            session.writeAlarms(saved.alarms)
            update(peer) { if (it.alarms == saved.alarms) it.copy(alarmsPending = false) else it }
        }
        if (saved.timerPending && saved.timerSeconds != null) optional(peer) {
            session.writeTimer(saved.timerSeconds)
            update(peer) { if (it.timerSeconds == saved.timerSeconds) it.copy(timerPending = false) else it }
        }
        if (saved.settingsPending && saved.settings != null) optional(peer) {
            session.writeSettings(saved.settings)
            update(peer) { if (it.settings == saved.settings) it.copy(settingsPending = false) else it }
        }
        if (saved.autoSyncPending && saved.autoSync != null) optional(peer) {
            session.writeAutoSync(saved.autoSync)
            update(peer) { if (it.autoSync == saved.autoSync) it.copy(autoSyncPending = false) else it }
        }
    }

    /** Reads what can change on the watch itself. Values with an unsent edit are left alone. */
    private suspend fun pull(peer: Connected) {
        val session = peer.session
        optional(peer) {
            val condition = session.readCondition()
            update(peer) { it.copy(batteryPercent = condition.batteryPercent, temperatureC = condition.temperatureC) }
        }
        optional(peer) {
            val alarms = session.readAlarms()
            update(peer) { if (it.alarmsPending) it else it.copy(alarms = alarms) }
        }
        optional(peer) {
            val timer = session.readTimer()
            update(peer) { if (it.timerPending) it else it.copy(timerSeconds = timer) }
        }
        optional(peer) {
            val settings = session.readSettings()
            update(peer) { if (it.settingsPending) it else it.copy(settings = settings) }
        }
        optional(peer) {
            val autoSync = session.readAutoSync()
            update(peer) { if (it.autoSyncPending) it else it.copy(autoSync = autoSync) }
        }
    }

    private suspend fun update(peer: Connected, transform: (SavedWatch) -> SavedWatch) =
        store.update { it.with(peer.address, transform) }

    /** One part of an exchange that the rest does not depend on: a refusal is shown, not thrown. */
    private suspend fun optional(peer: Connected, part: suspend () -> Unit) {
        try {
            part()
        } catch (e: ProtocolException) {
            error.value = Problem(peer.address, e.message)
        }
    }

    /** Starts the phone finder tone. A phone that cannot play it says so instead of failing the sync. */
    private fun ring(peer: Connected) {
        try {
            finder.start()
        } catch (e: Exception) {
            error.value = Problem(peer.address, "Phone finder could not ring: ${e.message}")
        }
    }

    private companion object {
        const val SCAN_SPACING_MS = 6_500L
        const val NTP_WAIT_MS = 2_500L
        const val EXCHANGE_WAKE_MS = 60_000L

        /** How long to let the watch end a one-shot connection itself before hanging up. */
        const val HANG_UP_GRACE_MS = 3_000L

        /** How long the link is kept after a phone finder request, to notice a button press. */
        const val FINDER_LINK_MS = 35_000L
        val FINDER_BUTTON_WINDOW_MS = 3_000L..28_000L
    }
}

/** Which watch the radio is busy with, if any, and how far that has got. */
private data class Link(val address: String? = null, val state: LinkState = LinkState.WAITING)

/** Something that went wrong, and with which watch; without an address it concerns all of them. */
private data class Problem(val address: String?, val message: String?)

/** The part of the state that is not stored: it describes this moment. */
private data class Live(
    val link: Link,
    val bluetoothOn: Boolean,
    val error: Problem?,
    val clock: ReferenceClock,
    val finderRinging: Boolean,
)

private fun toState(saved: Saved, live: Live): WatchState {
    val current = saved.current
    val address = current?.watch?.address
    return WatchState(
        watches = saved.watches.map { it.watch },
        watch = current?.watch,
        profile = current?.let { WatchProfiles.of(it.watch.name) },
        link = when {
            !live.bluetoothOn -> LinkState.BLUETOOTH_OFF
            live.link.address == address -> live.link.state
            else -> LinkState.WAITING
        },
        clock = live.clock,
        batteryPercent = current?.batteryPercent,
        temperatureC = current?.temperatureC,
        lastSync = current?.lastSync,
        lastError = live.error?.takeIf { it.address == null || it.address == address }?.message,
        alarms = current?.alarms,
        timerSeconds = current?.timerSeconds,
        settings = current?.settings,
        autoSync = current?.autoSync,
        pendingWrites = current != null &&
            (current.alarmsPending || current.timerPending || current.settingsPending || current.autoSyncPending),
        fineAdjustMs = current?.fineAdjustMs ?: 0,
        homeCity = current?.homeCity,
        worldCities = current?.worldCities.orEmpty(),
        diagnostics = current?.syncData?.takeIf { it.isNotEmpty() }?.let { (listOf(current.watch.name) + it).joinToString("\n") },
        phoneFinderEnabled = saved.phoneFinderEnabled,
        finderRinging = live.finderRinging,
    )
}
