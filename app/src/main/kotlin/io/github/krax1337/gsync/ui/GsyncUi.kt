package io.github.krax1337.gsync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState

enum class Tab(val label: String) {
    WATCH("Watch"),
    ALARMS("Alarms"),
    SETTINGS("Settings"),
}

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.WATCH -> WatchIcon
        Tab.ALARMS -> AlarmIcon
        Tab.SETTINGS -> Icons.Filled.Settings
    }

/** Switching tabs is a quick fade, short enough not to hold anything up. */
private const val TAB_FADE_MS = 150

/**
 * The whole app. Everything shown comes from [state] and every change goes through [actions]:
 * the pairing screen while no watch is paired, the three tabs afterwards.
 */
@Composable
fun GsyncUi(
    state: WatchState,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    initialTab: Tab = Tab.WATCH,
) {
    val watch = state.watch
    if (watch == null) {
        PairScreen(state, actions, permissionsGranted, onRequestPermissions)
    } else {
        Tabs(state, watch, actions, permissionsGranted, onRequestPermissions, initialTab)
    }
}

@Composable
private fun Tabs(
    state: WatchState,
    watch: Watch,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    initialTab: Tab,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Keeps each tab's scroll position while another tab is shown.
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar(
                windowInsets = ScreenInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = entry == tab,
                        onClick = { tab = entry },
                        icon = { Icon(imageVector = entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        contentWindowInsets = ScreenInsets,
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(TAB_FADE_MS)) togetherWith fadeOut(tween(TAB_FADE_MS)) },
            label = "tab",
        ) { shown ->
            tabStates.SaveableStateProvider(shown) {
                when (shown) {
                    Tab.WATCH -> WatchScreen(state, watch, actions, permissionsGranted, onRequestPermissions, padding)
                    Tab.ALARMS -> AlarmsScreen(state, actions, padding)
                    Tab.SETTINGS -> SettingsScreen(state, watch, actions, padding)
                }
            }
        }
    }
}
