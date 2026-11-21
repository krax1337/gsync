package io.github.krax1337.gsync.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.krax1337.gsync.protocol.AutoSync
import io.github.krax1337.gsync.protocol.Language
import io.github.krax1337.gsync.protocol.Trigger
import io.github.krax1337.gsync.watch.ClockSource
import io.github.krax1337.gsync.watch.LinkState
import io.github.krax1337.gsync.watch.SyncRecord
import io.github.krax1337.gsync.watch.Watch
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val SYNC_TIME_24 = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm:ss", Locale.ENGLISH)
private val SYNC_TIME_12 = DateTimeFormatter.ofPattern("EEE d MMM, h:mm:ss a", Locale.ENGLISH)

@Composable
internal fun WatchScreen(
    state: WatchState,
    watch: Watch,
    actions: WatchActions,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    val settings = state.settings
    // Until the watch has told how it is set up, assume it matches the phone.
    val use24Hour = settings?.use24Hour ?: DateFormat.is24HourFormat(context)
    val dayFirst = settings?.dayFirst ?: remember(context) {
        val order = DateFormat.getDateFormatOrder(context)
        order.indexOf('d') < order.indexOf('M')
    }

    ScreenColumn(padding) {
        Header(model = watch.model, link = state.link)
        Reveal(visible = !permissionsGranted) { PermissionBanner(onRequestPermissions) }
        RevealNotNull(value = state.lastError) { ErrorBanner(it) }

        Gap()
        ClockPanel(
            clock = state.clock,
            use24Hour = use24Hour,
            dayFirst = dayFirst,
            language = settings?.language ?: Language.ENGLISH,
        )
        Gap()
        LastSync(sync = state.lastSync, offsetMs = state.clock.offsetMs, use24Hour = use24Hour)
        Gap()
        StatTiles(battery = state.batteryPercent, temperature = state.temperatureC, autoSync = state.autoSync)

        Reveal(visible = state.link == LinkState.CONNECTED || state.link == LinkState.SYNCING) {
            ConnectedActions(
                canSync = state.link == LinkState.CONNECTED,
                onSync = actions::syncNow,
                onDisconnect = actions::disconnect,
            )
        }
        Reveal(visible = state.link == LinkState.WAITING) { ConnectHint() }
        Reveal(visible = state.pendingWrites) { PendingBanner() }
    }
}

@Composable
private fun Header(model: String, link: LinkState) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = model,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        StatusPill(link)
    }
}

/** What the leading mark of the status pill is; the two busy states share one spinner. */
private enum class Mark { LISTENING, BUSY, CONNECTED, ERROR }

/** Connection state as one quiet line; every change between states is a short fade. */
@Composable
private fun StatusPill(link: LinkState) {
    val scheme = MaterialTheme.colorScheme
    val label = when (link) {
        LinkState.WAITING -> "Listening for the watch"
        LinkState.CONNECTING -> "Connecting"
        LinkState.SYNCING -> "Syncing"
        LinkState.CONNECTED -> "Connected"
        LinkState.BLUETOOTH_OFF -> "Bluetooth is off"
    }
    val mark = when (link) {
        LinkState.WAITING -> Mark.LISTENING
        LinkState.CONNECTING, LinkState.SYNCING -> Mark.BUSY
        LinkState.CONNECTED -> Mark.CONNECTED
        LinkState.BLUETOOTH_OFF -> Mark.ERROR
    }
    val failed = link == LinkState.BLUETOOTH_OFF
    val container by animateColorAsState(
        targetValue = if (failed) scheme.errorContainer else scheme.surfaceContainerHigh,
        animationSpec = tween(MOTION_MS),
        label = "status container",
    )
    val content by animateColorAsState(
        targetValue = if (failed) scheme.onErrorContainer else scheme.onSurfaceVariant,
        animationSpec = tween(MOTION_MS),
        label = "status content",
    )

    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(start = 12.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crossfade(targetState = mark, animationSpec = tween(MOTION_MS), label = "status mark") {
                StatusMark(it)
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_MS)) },
                label = "status label",
            ) {
                Text(text = it, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusMark(mark: Mark) {
    Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        when (mark) {
            Mark.LISTENING -> PulsingDot(MaterialTheme.colorScheme.primary)
            Mark.BUSY -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = LocalContentColor.current,
                strokeWidth = 1.5.dp,
            )
            Mark.CONNECTED -> Dot(LocalWatchColors.current.connected)
            Mark.ERROR -> Dot(MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

/** Breathes while the app listens. The alpha is read while drawing, so nothing recomposes. */
@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "listening")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "listening alpha",
    )
    Box(Modifier.size(8.dp).drawBehind { drawCircle(color = color, alpha = alpha.value) })
}

@Composable
private fun PermissionBanner(onRequestPermissions: () -> Unit) {
    Panel(color = MaterialTheme.colorScheme.errorContainer) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                text = "Bluetooth access is missing. G-Sync cannot reach the watch without it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(
                onClick = onRequestPermissions,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Text("Allow access")
            }
        }
    }
}

@Composable
private fun LastSync(sync: SyncRecord?, offsetMs: Long, use24Hour: Boolean) {
    Panel {
        // A first sync, or a sync with one line more or less, resizes the card instead of jumping.
        Column(modifier = Modifier.animateContentSize(tween(MOTION_MS)).padding(16.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            if (sync == null) {
                Text(text = "Not synced yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "The watch gets the time at its next connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                val setAt = remember(sync.atMs, use24Hour) {
                    val formatter = if (use24Hour) SYNC_TIME_24 else SYNC_TIME_12
                    formatter.format(Instant.ofEpochMilli(sync.atMs).atZone(ZoneId.systemDefault()))
                }
                Text(text = "Last sync · $setAt", style = MaterialTheme.typography.labelLarge, color = muted)
                SyncAge(atMs = sync.atMs, offsetMs = offsetMs)
                Text(
                    text = "${sourceLabel(sync)} · ${triggerLabel(sync.trigger)} · ${sync.zone}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                phoneClockNote(sync)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

/** "12 min ago", moving on each time another whole minute has passed since the sync. */
@Composable
private fun SyncAge(atMs: Long, offsetMs: Long) {
    val minutes = rememberTicks(offsetMs = offsetMs, unitMs = MINUTE_MS, anchorMs = atMs)
    Text(text = relativeTime(minutes.longValue), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun StatTiles(battery: Int?, temperature: Int?, autoSync: AutoSync?) {
    val tiles: @Composable (Modifier) -> Unit = { tile ->
        StatTile(label = "Battery", value = battery?.let { "$it%" } ?: "–", modifier = tile)
        StatTile(label = "Temperature", value = temperature?.let { "$it\u00A0°C" } ?: "–", modifier = tile)
        StatTile(
            label = "Auto sync",
            value = when {
                autoSync == null -> "–"
                autoSync.enabled -> "4× a day"
                else -> "Off"
            },
            detail = autoSync?.takeIf { it.enabled }?.let { "at ${minuteLabel(it.minute)}" },
            modifier = tile,
        )
    }
    // The auto sync tile has a second line only while auto sync is on.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().animateContentSize(tween(MOTION_MS))) {
        // Side by side only while no label has to be cut: narrow screens with large text stack them.
        if (maxWidth >= STAT_TILE_MIN_WIDTH * 3 * LocalDensity.current.fontScale) {
            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { tiles(Modifier.fillMaxWidth()) }
        }
    }
}

private val STAT_TILE_MIN_WIDTH = 96.dp

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, detail: String? = null) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectedActions(canSync: Boolean, onSync: () -> Unit, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSync, enabled = canSync, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Sync now")
        }
        TextButton(onClick = onDisconnect) { Text("Disconnect") }
    }
}

private fun relativeTime(elapsedMinutes: Long): String =
    when {
        elapsedMinutes < 1 -> "Just now"
        elapsedMinutes < 60 -> "$elapsedMinutes min ago"
        elapsedMinutes < 48 * 60 -> "${elapsedMinutes / 60} h ago"
        else -> "${elapsedMinutes / (24 * 60)} days ago"
    }

private fun sourceLabel(sync: SyncRecord): String =
    when (sync.source) {
        ClockSource.NTP -> "Network time ±${sync.ntpRoundTripMs / 2} ms"
        ClockSource.PHONE -> "Phone clock"
    }

private fun triggerLabel(trigger: Trigger): String =
    when (trigger) {
        Trigger.AUTO_SYNC -> "Automatic"
        Trigger.TIME_BUTTON -> "Watch button"
        Trigger.CONNECT -> "Manual connection"
        Trigger.FIND_PHONE -> "Phone finder"
    }

/** "Phone clock was 412 ms slow": negative error means the phone was behind network time. */
private fun phoneClockNote(sync: SyncRecord): String? {
    val error = abs(sync.phoneClockErrorMs)
    if (sync.source != ClockSource.NTP || error < 100) return null
    val amount = if (error < 1000) "$error ms" else "%.1f s".format(Locale.ROOT, error / 1000.0)
    return "Phone clock was $amount ${if (sync.phoneClockErrorMs < 0) "slow" else "fast"}"
}
