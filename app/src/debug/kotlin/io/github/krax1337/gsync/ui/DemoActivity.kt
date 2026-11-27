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
import io.github.krax1337.gsync.protocol.Setting
import io.github.krax1337.gsync.protocol.Support
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.protocol.WatchProfiles
import io.github.krax1337.gsync.protocol.WatchSettings
import io.github.krax1337.gsync.watch.ClockSource
import io.github.krax1337.gsync.watch.LinkState
import io.github.krax1337.gsync.watch.ReferenceClock
import io.github.krax1337.gsync.watch.SyncRecord
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * Debug-only: every screen with sample data, for screenshots on an emulator without a watch.
 *
 *     adb shell am start -n io.github.krax1337.gsync/.ui.DemoActivity --es screen alarms --ez dark true
 *
 * `screen` is one of pair, pair_noperm, watch, watch_connected, watch_empty, mismatch, add,
 * finder, experimental, alarms, alarms_pending, settings, experimental_settings, guide,
 * diagnostics, licences. Without `dark` the system setting applies.
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
        val actions = DemoActions(paired = state.watches)
        setContent {
            GsyncTheme(darkOverride = dark) {
                GsyncUi(
                    state = state,
                    actions = actions,
                    permissionsGranted = screen != "pair_noperm",
                    onRequestPermissions = {},
                    initialTab = when (screen) {
                        "alarms", "alarms_pending" -> Tab.ALARMS
                        "settings", "experimental_settings", "guide", "diagnostics", "licences" -> Tab.SETTINGS
                        else -> Tab.WATCH
                    },
                    initialHelpPage = when (screen) {
                        "guide" -> HelpPage.GUIDE
                        "diagnostics" -> HelpPage.DIAGNOSTICS
                        "licences" -> HelpPage.LICENCES
                        else -> null
                    },
                    initialAddingWatch = screen == "add",
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

/** The same model again, so the switcher has to tell the two apart by address. */
private val SECOND_WATCH = Watch("F0:9A:11:5C:27:B8", "CASIO GW-BX5600")

/** A model nobody has confirmed the app on, as the `experimental` screens show it. */
private val EXPERIMENTAL_WATCH = Watch("E1:7A:22:10:9B:44", "CASIO GW-B5600")

/** One watch for each tag of the pairing list. */
private val NEARBY = listOf(
    Watch("C8:14:7E:3A:90:12", "CASIO GW-BX5600"),
    EXPERIMENTAL_WATCH,
    Watch("F4:2B:6D:08:51:A3", "CASIO ECB-30"),
)

private fun sampleState(screen: String): WatchState {
    val synced = WatchState(
        watches = listOf(SAMPLE_WATCH, SECOND_WATCH),
        watch = SAMPLE_WATCH,
        profile = WatchProfiles.of(SAMPLE_WATCH.name),
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
        homeCity = "KARACHI",
        worldCities = listOf("NEW YORK", "TOKYO"),
        diagnostics = """
            DST watch state 0: 1D 00 01 03 02 7F 76 00 00 FF FF FF FF FF FF
            DST setting 0: 1E 00 5B 00 14 05 00
            World city 0: 1F 00 4B 41 52 41 43 48 49 00 00 00 00 00 00 00 00 00 00 00
        """.trimIndent(),
    )
    val experimental = synced.copy(
        watches = listOf(SAMPLE_WATCH, EXPERIMENTAL_WATCH),
        watch = EXPERIMENTAL_WATCH,
        // Written out rather than looked up, so the screens do not change with the list of models.
        profile = WatchProfiles.of(SAMPLE_WATCH.name).run {
            copy(
                model = "GW-B5600",
                support = Support.EXPERIMENTAL,
                settings = settings - setOf(Setting.DIGITS, Setting.POWER_SAVING),
                hasTemperature = false,
            )
        },
        temperatureC = null,
    )
    return when (screen) {
        "pair", "pair_noperm" -> WatchState()
        "watch_connected" -> synced.copy(link = LinkState.CONNECTED)
        "watch_empty" -> WatchState(
            watches = listOf(SAMPLE_WATCH),
            watch = SAMPLE_WATCH,
            profile = WatchProfiles.of(SAMPLE_WATCH.name),
        )
        "experimental", "experimental_settings" -> experimental
        "mismatch" -> synced.copy(homeCity = "TOKYO")
        "finder" -> synced.copy(finderRinging = true)
        "alarms_pending" -> synced.copy(pendingWrites = true)
        else -> synced
    }
}

/** Changes nothing; pairing finds the nearby watches that are not paired. */
private class DemoActions(private val paired: List<Watch>) : WatchActions {
    override fun discover(): Flow<Watch> = NEARBY.filterNot { it in paired }.asFlow()

    override fun pair(watch: Watch) = Unit

    override fun unpair() = Unit

    override fun select(address: String) = Unit

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
