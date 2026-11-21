package io.github.krax1337.gsync.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.krax1337.gsync.watch.LinkState
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState

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
                    text = "G-Sync keeps a G-Shock GW-BX5600 on accurate time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Gap()
            when {
                !permissionsGranted -> PermissionCard(onRequestPermissions)
                state.link == LinkState.BLUETOOTH_OFF ->
                    ErrorBanner("Bluetooth is off. Turn it on to look for the watch.")
                else -> Discovery(actions)
            }
            RevealNotNull(value = state.lastError) { ErrorBanner(it) }
        }
    }
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
            Step(number = 1, text = "Hold the lower-left button for about 3 seconds until CONNECT appears.")
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
                    SettingRow(
                        title = watch.name,
                        supporting = watch.address,
                        onClick = {
                            haptics.confirm()
                            actions.pair(watch)
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}
