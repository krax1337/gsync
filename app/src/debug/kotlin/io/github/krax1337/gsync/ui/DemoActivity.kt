package io.github.krax1337.gsync.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.krax1337.gsync.protocol.Alarm
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.watch.ClockSource
import io.github.krax1337.gsync.watch.LinkState
import io.github.krax1337.gsync.watch.ReferenceClock
import io.github.krax1337.gsync.watch.SyncRecord
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Debug-only: every screen with sample data, for screenshots on an emulator without a watch.
 *
 *     adb shell am start -n io.github.krax1337.gsync/.ui.DemoActivity --es screen alarms --ez dark true
 *
 * `screen` is one of pair, pair_noperm, watch, watch_connected, watch_empty, finder, alarms,
 * alarms_pending, settings, guide. Without `dark` the system setting applies.
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = if (intent.hasExtra(EXTRA_DARK)) intent.getBooleanExtra(EXTRA_DARK, false) else null
        if (dark == null) {
            enableEdgeToEdge()
        } else {
            // Bar icons have to follow the forced theme, not the system one.
            val bars = if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
        super.onCreate(savedInstanceState)

        val screen = intent.getStringExtra(EXTRA_SCREEN) ?: "watch"
        val state = sampleState(screen)
        setContent {
            GsyncTheme(darkOverride = dark) {
                GsyncUi(
                    state = state,
                    actions = DemoActions,
                    permissionsGranted = screen != "pair_noperm",
                    onRequestPermissions = {},
                    initialTab = when (screen) {
                        "alarms", "alarms_pending" -> Tab.ALARMS
                        "settings", "guide" -> Tab.SETTINGS
                        else -> Tab.WATCH
                    },
                    initialGuideOpen = screen == "guide",
                )
            }
        }
    }

    private companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_DARK = "dark"
    }
}

private val SAMPLE_WATCH = Watch("D3:60:41:AB:CD:EF", "CASIO GW-BX5600")

private fun sampleState(screen: String): WatchState {
    val synced = WatchState(
        watch = SAMPLE_WATCH,
        link = LinkState.WAITING,
        clock = ReferenceClock(offsetMs = 412, source = ClockSource.NTP),
        batteryPercent = 80,
        temperatureC = 27,
        lastSync = SyncRecord(
            atMs = System.currentTimeMillis() - 12 * 60_000,
            trigger = Trigger.AUTO_SYNC,
            source = ClockSource.NTP,
            ntpRoundTripMs = 18,
            phoneClockErrorMs = -412,
            zone = "Asia/Almaty",
        ),
        alarms = AlarmSet(
            alarms = listOf(
                Alarm(hour = 6, minute = 30, enabled = true),
                Alarm(hour = 7, minute = 15, enabled = true),
                Alarm(hour = 9, minute = 0, enabled = false),
                Alarm(hour = 13, minute = 45, enabled = false),
                Alarm(hour = 22, minute = 30, enabled = false),
            ),
            hourlyChime = false,
        ),
        timerSeconds = 600,
        settings = WatchSettings(
            use24Hour = true,
            buttonTone = false,
            autoLight = true,
            powerSaving = true,
            longLight = false,
            dayFirst = true,
            language = Language.ENGLISH,
            classicFont = true,
        ),
        autoSync = AutoSync(enabled = true, minute = 30),
    )
    return when (screen) {
        "pair", "pair_noperm" -> WatchState()
        "watch_connected" -> synced.copy(link = LinkState.CONNECTED)
        "watch_empty" -> WatchState(watch = SAMPLE_WATCH)
        "finder" -> synced.copy(finderRinging = true)
        "alarms_pending" -> synced.copy(pendingWrites = true)
        else -> synced
    }
}

/** Changes nothing; pairing finds two watches. */
private object DemoActions : WatchActions {
    override fun discover(): Flow<Watch> = flowOf(SAMPLE_WATCH, Watch("E1:7A:22:10:9B:44", "CASIO GMW-B5000"))

    override fun pair(watch: Watch) = Unit

    override fun unpair() = Unit

    override fun setAlarms(alarms: AlarmSet) = Unit

    override fun setTimer(seconds: Int) = Unit

    override fun setSettings(settings: WatchSettings) = Unit

    override fun setAutoSync(autoSync: AutoSync) = Unit

    override fun setFineAdjust(ms: Int) = Unit

    override fun setPhoneFinderEnabled(enabled: Boolean) = Unit

    override fun stopFinder() = Unit

    override fun syncNow() = Unit

    override fun disconnect() = Unit
}
