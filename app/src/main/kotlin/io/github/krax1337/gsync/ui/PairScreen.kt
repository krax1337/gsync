package io.github.krax1337.gsync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.krax1337.gsync.protocol.Support
import io.github.krax1337.gsync.protocol.WatchProfiles
import io.github.krax1337.gsync.watch.LinkState
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState

/** The first watch: all there is to see until one is paired. */
@Composable
internal fun PairScreen(
    state: WatchState,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    Scaffold(contentWindowInsets = ScreenInsets) { padding ->
        ScreenColumn(padding) {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pair your watch",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "G-Sync keeps a Casio G-Shock on accurate time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Gap()
            Pairing(state, actions, permissionsGranted, onRequestPermissions)
        }
    }
}

/** A further watch: the same search as a page in place of the tabs, left with [onBack]. */
@Composable
internal fun AddWatchScreen(
    state: WatchState,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        PageTopBar(title = "Add a watch", onBack = onBack)
        ScreenColumn(PaddingValues(0.dp)) {
            Text(
                text = "Your other watches stay paired. " +
                    "Tap the watch name at the top of the Watch tab to switch between them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap()
            Pairing(state, actions, permissionsGranted, onRequestPermissions)
        }
    }
}

@Composable
private fun ColumnScope.Pairing(
    state: WatchState,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    when {
        !permissionsGranted -> PermissionCard(onRequestPermissions)
        state.link == LinkState.BLUETOOTH_OFF ->
            ErrorBanner("Bluetooth is off. Turn it on to look for the watch.")
        else -> Discovery(actions)
    }
    RevealNotNull(value = state.lastError) { ErrorBanner(it) }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    Panel {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Bluetooth access", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "G-Sync needs Bluetooth access to find the watch and to set its time " +
                        "in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRequestPermissions) { Text("Allow access") }
        }
    }
}

@Composable
private fun ColumnScope.Discovery(actions: WatchActions) {
    val haptics = LocalHapticFeedback.current
    val found = remember(actions) { mutableStateListOf<Watch>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Collecting the flow is what scans, so it only runs while this screen is on display.
    LaunchedEffect(actions, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            actions.discover().collect { watch ->
                if (found.none { it.address == watch.address }) found += watch
            }
        }
    }

    Panel {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(number = 1, text = "Start a connection on the watch: on most models, hold the lower-left button for about 3 seconds until CONNECT appears.")
            Step(number = 2, text = "Tap the watch when it shows up below.")
        }
    }

    Gap()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (found.isEmpty()) "Looking for the watch" else "Nearby watches",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    Reveal(visible = found.isNotEmpty(), gap = 8.dp) {
        // Grows as further watches turn up.
        Panel(modifier = Modifier.animateContentSize(tween(MOTION_MS))) {
            found.forEachIndexed { index, watch ->
                key(watch.address) {
                    if (index > 0) RowDivider()
                    FoundWatchRow(
                        watch = watch,
                        onPair = {
                            haptics.confirm()
                            actions.pair(watch)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A watch that turned up, tagged with how far G-Sync is confirmed on its model. One the app cannot
 * serve stays in the list, so that it is seen to be found, but cannot be paired.
 */
@Composable
private fun FoundWatchRow(watch: Watch, onPair: () -> Unit) {
    val support = remember(watch.name) { WatchProfiles.of(watch.name).support }
    if (support == Support.UNSUPPORTED) {
        SettingRow(
            title = watch.name,
            // Not clickable, so the row is made one item for the screen reader here, and a disabled one.
            modifier = Modifier.semantics(mergeDescendants = true) { disabled() },
            supporting = "This model does not work with G-Sync yet",
            titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            trailing = { SupportTag(support) },
        )
    } else {
        SettingRow(
            title = watch.name,
            supporting = watch.address,
            onClick = onPair,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SupportTag(support)
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** How far G-Sync is confirmed on a model, in a word or two at the end of the row. */
@Composable
private fun SupportTag(support: Support) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (support) {
        Support.VERIFIED -> Triple("Confirmed", scheme.secondaryContainer, scheme.onSecondaryContainer)
        Support.EXPERIMENTAL -> Triple("Experimental", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        Support.UNSUPPORTED -> Triple("Not supported", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
