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
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchSettings
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
 * Owns the watch: listens for it, serves each connection, and keeps what the UI shows.
 *
 * The watch is a peripheral that advertises only when it wants to talk (four times a day, or on a
 * button press), and only for a short while. [run] keeps a filtered scan going and connects the
 * moment the watch shows up.
 */
@SuppressLint("MissingPermission") // run() and discover() are only reached once Bluetooth permissions are granted.
@OptIn(ExperimentalCoroutinesApi::class)
class WatchManager(private val context: Context) : WatchActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = WatchStore(context)
    private val clock = NetworkClock(SystemClock::elapsedRealtime, System::currentTimeMillis)
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val link = MutableStateFlow(LinkState.WAITING)
    private val bluetoothOn = MutableStateFlow(adapter?.isEnabled == true)
    private val error = MutableStateFlow<String?>(null)
    private val reference = MutableStateFlow(ReferenceClock())
    private val uiVisible = MutableStateFlow(false)

    /** Set while the watch stays connected after its initial exchange. */
    @Volatile
    private var connected: Connected? = null
    private val exchange = Mutex()
    private var lastScanStartMs = -SCAN_SPACING_MS

    private class Connected(val gatt: GattLink, val session: WatchSession)

    val state: StateFlow<WatchState> =
        combine(store.saved, link, bluetoothOn, error, reference, ::toState)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                toState(runBlocking { store.saved.first() }, link.value, bluetoothOn.value, error.value, reference.value),
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

    /** Serves the paired watch until cancelled. Returns when no watch is paired. */
    suspend fun run() {
        val adapter = adapter ?: return
        store.saved.map { it.watch?.address }
            .distinctUntilChanged()
            .transformLatest { address -> if (address == null) emit(Unit) else serve(adapter, address) }
            .first()
    }

    /** Completes without a result when Bluetooth is off or refuses to scan. */
    override fun discover(): Flow<Watch> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (name.startsWith("CASIO", ignoreCase = true)) trySend(Watch(result.device.address, name))
            }

            override fun onScanFailed(errorCode: Int) {
                close()
            }
        }
        if (scanner == null) close() else scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner?.stopScan(callback) } }
    }

    override fun pair(watch: Watch) = edit { Saved(watch = watch, fineAdjustMs = it.fineAdjustMs) }

    override fun unpair() = edit { Saved() }

    override fun setAlarms(alarms: AlarmSet) = edit { it.copy(alarms = alarms, alarmsPending = true) }

    override fun setTimer(seconds: Int) = edit { it.copy(timerSeconds = seconds, timerPending = true) }

    override fun setSettings(settings: WatchSettings) = edit { it.copy(settings = settings, settingsPending = true) }

    override fun setAutoSync(autoSync: AutoSync) = edit { it.copy(autoSync = autoSync, autoSyncPending = true) }

    override fun setFineAdjust(ms: Int) = edit { it.copy(fineAdjustMs = ms) }

    override fun syncNow() {
        whileConnected { session ->
            refreshClock()
            syncTime(session, Trigger.CONNECT)
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

    /** Persists an edit, then writes it to the watch if one is connected. */
    private fun edit(transform: (Saved) -> Saved) {
        scope.launch {
            store.update(transform)
            whileConnected { pushPending(it) }.join()
        }
    }

    private fun whileConnected(block: suspend (WatchSession) -> Unit) = scope.launch {
        exchange.withLock {
            val session = connected?.session ?: return@launch
            try {
                block(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    private suspend fun serve(adapter: BluetoothAdapter, address: String): Nothing {
        val device = adapter.getRemoteDevice(address)
        while (true) {
            try {
                awaitAdvertisement(adapter, address)
                link.value = LinkState.CONNECTING
                connect(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    private suspend fun awaitAdvertisement(adapter: BluetoothAdapter, address: String) {
        combine(bluetoothOn, uiVisible, ::Pair)
            .mapLatest { (on, visible) ->
                if (!on) awaitCancellation()
                link.value = LinkState.WAITING
                // A filtered low-power scan keeps running with the screen off. While the app is
                // open, scan continuously so a button press connects at once.
                scanFor(adapter, address, if (visible) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            }
            .first()
    }

    private suspend fun scanFor(adapter: BluetoothAdapter, address: String, mode: Int) {
        // Android silently ignores an app that starts more than five scans in 30 seconds.
        delay(lastScanStartMs + SCAN_SPACING_MS - SystemClock.elapsedRealtime())
        val scanner = adapter.bluetoothLeScanner ?: awaitCancellation()
        val found = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(Unit)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IOException("Bluetooth scan failed ($errorCode)"))
            }
        }
        lastScanStartMs = SystemClock.elapsedRealtime()
        scanner.startScan(
            listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
            ScanSettings.Builder().setScanMode(mode).build(),
            callback,
        )
        try {
            found.await()
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
            val session = WatchSession(gatt, SystemClock::elapsedRealtime)
            link.value = LinkState.SYNCING
            val trigger = exchange.withLock {
                val trigger = session.hello()
                error.value = null
                // Edits first: the watch may hang up as soon as an automatic sync has its time.
                pushPending(session)
                withTimeoutOrNull(NTP_WAIT_MS) { ntp.await() }
                syncTime(session, trigger)
                try {
                    pull(session)
                } catch (_: LinkLostException) {
                    // The time is set; whatever was not read is still current from last time.
                }
                trigger
            }
            if (trigger.staysConnected) {
                connected = Connected(gatt, session)
                link.value = LinkState.CONNECTED
                if (wakeLock.isHeld) wakeLock.release()
                whileConnected { pushPending(it) } // Edits made during the exchange above.
                gatt.awaitLost()
            } else {
                withTimeoutOrNull(HANG_UP_GRACE_MS) { gatt.awaitLost() }
            }
        } finally {
            connected = null
            gatt?.close()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun syncTime(session: WatchSession, trigger: Trigger) {
        val adjust = store.saved.first().fineAdjustMs
        val zone = ZoneId.systemDefault()
        val setTo = session.syncTime(zone) { clock.now().epochMs + adjust }
        val reading = clock.now()
        val record = SyncRecord(
            atMs = setTo - adjust,
            trigger = trigger,
            source = if (reading.sample != null) ClockSource.NTP else ClockSource.PHONE,
            ntpRoundTripMs = reading.sample?.roundTripMs?.toInt() ?: 0,
            phoneClockErrorMs = if (reading.sample != null) System.currentTimeMillis() - reading.epochMs else 0,
            zone = zone.id,
        )
        store.update { it.copy(lastSync = record) }
    }

    /**
     * Writes edits made since the last connection. An edit made meanwhile stays pending, and so
     * does one the watch refuses: it is reported and must not keep the time from being set.
     */
    private suspend fun pushPending(session: WatchSession) {
        val saved = store.saved.first()
        if (saved.alarmsPending && saved.alarms != null) optional {
            session.writeAlarms(saved.alarms)
            store.update { if (it.alarms == saved.alarms) it.copy(alarmsPending = false) else it }
        }
        if (saved.timerPending && saved.timerSeconds != null) optional {
            session.writeTimer(saved.timerSeconds)
            store.update { if (it.timerSeconds == saved.timerSeconds) it.copy(timerPending = false) else it }
        }
        if (saved.settingsPending && saved.settings != null) optional {
            session.writeSettings(saved.settings)
            store.update { if (it.settings == saved.settings) it.copy(settingsPending = false) else it }
        }
        if (saved.autoSyncPending && saved.autoSync != null) optional {
            session.writeAutoSync(saved.autoSync)
            store.update { if (it.autoSync == saved.autoSync) it.copy(autoSyncPending = false) else it }
        }
    }

    /** Reads what can change on the watch itself. Values with an unsent edit are left alone. */
    private suspend fun pull(session: WatchSession) {
        optional {
            val condition = session.readCondition()
            store.update { it.copy(batteryPercent = condition.batteryPercent, temperatureC = condition.temperatureC) }
        }
        optional {
            val alarms = session.readAlarms()
            store.update { if (it.alarmsPending) it else it.copy(alarms = alarms) }
        }
        optional {
            val timer = session.readTimer()
            store.update { if (it.timerPending) it else it.copy(timerSeconds = timer) }
        }
        optional {
            val settings = session.readSettings()
            store.update { if (it.settingsPending) it else it.copy(settings = settings) }
        }
        optional {
            val autoSync = session.readAutoSync()
            store.update { if (it.autoSyncPending) it else it.copy(autoSync = autoSync) }
        }
    }

    /** One part of an exchange that the rest does not depend on: a refusal is shown, not thrown. */
    private suspend fun optional(part: suspend () -> Unit) {
        try {
            part()
        } catch (e: ProtocolException) {
            error.value = e.message
        }
    }

    private companion object {
        const val SCAN_SPACING_MS = 6_500L
        const val NTP_WAIT_MS = 2_500L
        const val EXCHANGE_WAKE_MS = 60_000L

        /** How long to let the watch end a one-shot connection itself before hanging up. */
        const val HANG_UP_GRACE_MS = 3_000L
    }
}

private fun toState(saved: Saved, link: LinkState, bluetoothOn: Boolean, error: String?, clock: ReferenceClock) = WatchState(
    watch = saved.watch,
    link = if (bluetoothOn) link else LinkState.BLUETOOTH_OFF,
    clock = clock,
    batteryPercent = saved.batteryPercent,
    temperatureC = saved.temperatureC,
    lastSync = saved.lastSync,
    lastError = error,
    alarms = saved.alarms,
    timerSeconds = saved.timerSeconds,
    settings = saved.settings,
    autoSync = saved.autoSync,
    pendingWrites = saved.alarmsPending || saved.timerPending || saved.settingsPending || saved.autoSyncPending,
    fineAdjustMs = saved.fineAdjustMs,
)
