package io.github.krax1337.gsync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.krax1337.gsync.protocol.WatchProfile
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState

enum class Tab(val label: String) {
    WATCH("Watch"),
    ALARMS("Alarms"),
    SETTINGS("Settings"),
}

/** A page that takes the place of the settings list. */
enum class HelpPage { GUIDE, DIAGNOSTICS }

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
 * the pairing screen while no watch is paired, the three tabs afterwards. [initialHelpPage]
 * starts the Settings tab on that page, [initialAddingWatch] starts on pairing a further watch.
 */
@Composable
fun GsyncUi(
    state: WatchState,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    initialTab: Tab = Tab.WATCH,
    initialHelpPage: HelpPage? = null,
    initialAddingWatch: Boolean = false,
) {
    val watch = state.watch
    val profile = state.profile
    if (watch == null || profile == null) {
        PairScreen(state, actions, permissionsGranted, onRequestPermissions)
    } else {
        Tabs(
            state = state,
            watch = watch,
            profile = profile,
            actions = actions,
            permissionsGranted = permissionsGranted,
            onRequestPermissions = onRequestPermissions,
            initialTab = initialTab,
            initialHelpPage = initialHelpPage,
            initialAddingWatch = initialAddingWatch,
        )
    }
}

@Composable
private fun Tabs(
    state: WatchState,
    watch: Watch,
    profile: WatchProfile,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    initialTab: Tab,
    initialHelpPage: HelpPage?,
    initialAddingWatch: Boolean,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Lives here and not in the Settings tab, because the Watch tab opens these pages as well.
    var helpPage by rememberSaveable { mutableStateOf(initialHelpPage) }
    // A further watch is being paired: its page takes the place of the tabs and their bar.
    var addingWatch by rememberSaveable { mutableStateOf(initialAddingWatch) }
    // Keeps each tab's scroll position while another tab is shown.
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            // Always as tall as the status bar, so the tab below keeps its place while the phone
            // finder banner comes and goes.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(ScreenInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Reveal(visible = state.finderRinging, gap = 8.dp) {
                    Box(Modifier.widthIn(max = 600.dp).padding(horizontal = 16.dp)) {
                        FinderBanner(onStop = actions::stopFinder)
                    }
                }
            }
        },
        bottomBar = {
            if (!addingWatch) {
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
            }
        },
        contentWindowInsets = ScreenInsets,
    ) { padding ->
        if (addingWatch) {
            // Pairing puts the new watch on screen, so the page closes by itself onto the Watch tab.
            val pairedBefore = rememberSaveable { state.watches.size }
            LaunchedEffect(state.watches.size) {
                if (state.watches.size > pairedBefore) {
                    tab = Tab.WATCH
                    addingWatch = false
                }
            }
            AddWatchScreen(
                state = state,
                actions = actions,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = onRequestPermissions,
                padding = padding,
                onBack = { addingWatch = false },
            )
        } else {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(TAB_FADE_MS)) togetherWith fadeOut(tween(TAB_FADE_MS)) },
                label = "tab",
            ) { shown ->
                tabStates.SaveableStateProvider(shown) {
                    when (shown) {
                        Tab.WATCH -> WatchScreen(
                            state = state,
                            watch = watch,
                            profile = profile,
                            actions = actions,
                            permissionsGranted = permissionsGranted,
                            onRequestPermissions = onRequestPermissions,
                            padding = padding,
                            onOpenHelp = {
                                helpPage = it
                                tab = Tab.SETTINGS
                            },
                            onAddWatch = { addingWatch = true },
                        )
                        Tab.ALARMS -> AlarmsScreen(state, profile, actions, padding)
                        Tab.SETTINGS -> SettingsScreen(
                            state = state,
                            watch = watch,
                            profile = profile,
                            actions = actions,
                            padding = padding,
                            helpPage = helpPage,
                            onHelpPageChange = { helpPage = it },
                        )
                    }
                }
            }
        }
    }
}
