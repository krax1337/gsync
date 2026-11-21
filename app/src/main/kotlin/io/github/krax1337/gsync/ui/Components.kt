package io.github.krax1337.gsync.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.krax1337.gsync.protocol.WatchCities
import io.github.krax1337.gsync.protocol.WatchCity
import io.github.krax1337.gsync.protocol.WatchProfile
import io.github.krax1337.gsync.watch.Watch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

internal const val SECOND_MS = 1_000L
internal const val MINUTE_MS = 60_000L

/** Duration of every state change on screen: long enough to follow, too short to wait for. */
internal const val MOTION_MS = 200

/** "00" to "59": every two-digit field of a clock, without formatting one on each tick. */
internal val TWO_DIGITS: List<String> = List(60) { "%02d".format(Locale.ROOT, it) }

/** "GW-BX5600" from the advertised "CASIO GW-BX5600". */
internal val Watch.model: String
    get() = name.removePrefix("CASIO ").trim().ifEmpty { name }

/** "CD:EF" from "D3:60:41:AB:CD:EF": enough to tell two watches of one model apart. */
internal val Watch.addressTail: String
    get() = address.takeLast(5)

/** The watch guide is written for the GW-BX5600: its buttons, its menus. */
internal val WatchProfile.hasGuide: Boolean
    get() = model.startsWith("GW-BX5600")

/** ":05" for minute 5. */
internal fun minuteLabel(minute: Int): String = ":%02d".format(Locale.ROOT, minute)

/**
 * Whole [unitMs] periods of reference time (the phone clock corrected by [offsetMs]) that have
 * passed since [anchorMs]. The value changes exactly on each boundary: the loop sleeps until the
 * next one instead of polling, and only runs while the screen is at least started.
 */
@Composable
internal fun rememberTicks(offsetMs: Long, unitMs: Long, anchorMs: Long = 0): LongState {
    val ticks = remember(offsetMs, unitMs, anchorMs) {
        mutableLongStateOf(Math.floorDiv(System.currentTimeMillis() + offsetMs - anchorMs, unitMs))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(ticks, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val elapsed = System.currentTimeMillis() + offsetMs - anchorMs
                ticks.longValue = Math.floorDiv(elapsed, unitMs)
                delay(unitMs - Math.floorMod(elapsed, unitMs))
            }
        }
    }
    return ticks
}

/*
 * Haptics. The dedicated toggle and tick effects exist from Android 14; before that the nearest
 * equally light effect is the text handle tick.
 */
private val hasFineHaptics = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** A switch changed. */
internal fun HapticFeedback.toggle(on: Boolean) =
    performHapticFeedback(
        when {
            !hasFineHaptics -> HapticFeedbackType.TextHandleMove
            on -> HapticFeedbackType.ToggleOn
            else -> HapticFeedbackType.ToggleOff
        },
    )

/** One of several options was picked. */
internal fun HapticFeedback.tick() =
    performHapticFeedback(if (hasFineHaptics) HapticFeedbackType.SegmentTick else HapticFeedbackType.TextHandleMove)

/** A dialog was confirmed. */
internal fun HapticFeedback.confirm() = performHapticFeedback(HapticFeedbackType.Confirm)

/**
 * What screen content has to stay clear of: the system bars and the display cutout. The keyboard
 * is left out on purpose; it only ever opens over a dialog.
 */
internal val ScreenInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Scrolling screen body: 16dp padding, capped width on large screens. Blocks are separated with
 * [Gap]; optional ones go in [Reveal], which brings its own gap. A caller that takes the body out
 * of the composition and brings it back passes its own [scrollState] to keep the position.
 */
@Composable
internal fun ScreenColumn(
    padding: PaddingValues,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scrollState),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(16.dp),
            content = content,
        )
    }
}

/** The space between two blocks of a screen. */
@Composable
internal fun Gap() {
    Spacer(Modifier.height(16.dp))
}

/**
 * A block that is not always there. It expands into place and collapses again, together with
 * the [gap] above it, so nothing below jumps. Rows inside a panel take no gap.
 */
@Composable
internal fun ColumnScope.Reveal(visible: Boolean, gap: Dp = 16.dp, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(MOTION_MS)) + fadeIn(tween(MOTION_MS)),
        exit = shrinkVertically(tween(MOTION_MS)) + fadeOut(tween(MOTION_MS)),
    ) {
        Box(Modifier.padding(top = gap)) { content() }
    }
}

/** [Reveal] for a block that shows [value] while there is one. */
@Composable
internal fun <T : Any> ColumnScope.RevealNotNull(value: T?, content: @Composable (T) -> Unit) {
    // The last value stays on screen while the block collapses.
    val last = remember { Ref<T>() }
    if (value != null) last.value = value
    Reveal(visible = value != null) { last.value?.let { content(it) } }
}

@Composable
internal fun ScreenTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** Top bar of a page that takes the place of a screen. Stays in place above the scrolling body. */
@Composable
internal fun PageTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
    }
}

/** Rounded container that rows and card content sit in. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color) {
        Column(content = content)
    }
}

/** A small header with a [Panel] of rows under it. */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Panel(content = content)
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/** List row: title, optional supporting text, optional trailing control. At least 56dp tall. */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** [SettingRow] where the whole row is the switch. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    SettingRow(
        title = title,
        supporting = supporting,
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = {
                haptics.toggle(it)
                onCheckedChange(it)
            },
        ),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** Current value shown at the end of a row that opens a dialog. */
@Composable
internal fun ValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun EmptyCard(title: String, text: String? = null) {
    Panel {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PendingBanner() {
    Banner(
        text = "Changes waiting. They are written at the next connection.",
        icon = Icons.Outlined.Edit,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    )
}

@Composable
internal fun ErrorBanner(message: String) {
    Banner(text = message, icon = Icons.Outlined.Warning, color = MaterialTheme.colorScheme.errorContainer)
}

/**
 * How to make the watch connect; shown wherever the app is waiting for it. Ends in the way to the
 * guide when there is one for the watch.
 */
@Composable
internal fun ConnectHint(onOpenGuide: (() -> Unit)?) {
    Banner(
        text = "Press the lower-right button on the watch to sync now. " +
            "Hold the lower-left button for 3 seconds to stay connected.",
        icon = Icons.Outlined.Info,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        actionLabel = "Watch guide".takeIf { onOpenGuide != null },
        onAction = onOpenGuide ?: {},
    )
}

/** With an [actionLabel] the banner ends in a text button that calls [onAction]. */
@Composable
private fun Banner(
    text: String,
    icon: ImageVector,
    color: Color,
    contentColor: Color = contentColorFor(color),
    border: BorderStroke? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color,
        contentColor = contentColor,
        border = border,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = if (actionLabel == null) 16.dp else 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * The watch is ringing this phone. Shown above every tab until the tone stops, with the one
 * control that matters large enough to hit without looking.
 */
@Composable
internal fun FinderBanner(onStop: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Phone finder",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = "The watch is looking for this phone.", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    haptics.confirm()
                    onStop()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(text = "Stop", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** One numbered step of an instruction. */
@Composable
internal fun Step(number: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(24.dp).background(scheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSecondaryContainer,
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** What the app says about the time zone this phone is in. */
internal class PhoneZone(
    val id: String,
    /** "UTC+5", or "UTC+1 standard time" while the zone is on summer time. */
    val offset: String,
    /** The cities to choose on the watch for this zone; none when the watch has no city there. */
    val matches: List<WatchCity>,
) {
    /** "SIN SINGAPORE, HKG HONG KONG or TPE TAIPEI"; empty when the watch has no city there. */
    val cities: String = matches.map { it.label(it.name) }.let { labels ->
        if (labels.size < 2) labels.joinToString() else labels.dropLast(1).joinToString() + " or " + labels.last()
    }

    companion object {
        fun of(zone: ZoneId, at: Instant): PhoneZone {
            // Standard time is what the watch's cities are listed by, and what they are matched on.
            val standard = utcOffsetLabel(zone.rules.getStandardOffset(at).totalSeconds / 60)
            return PhoneZone(
                id = zone.id,
                offset = if (zone.rules.isDaylightSavings(at)) "$standard standard time" else standard,
                matches = WatchCities.matching(zone, at),
            )
        }
    }
}

/**
 * The zone this phone is in. Read again whenever the app comes back to the front: the phone
 * changes zone while the app is away, on a flight most of all.
 */
@Composable
internal fun rememberPhoneZone(): PhoneZone {
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    LifecycleResumeEffect(Unit) {
        zone = ZoneId.systemDefault()
        onPauseOrDispose { }
    }
    return remember(zone) { PhoneZone.of(zone, Instant.now()) }
}

/** "KHI KARACHI" for the [name] given; the name alone for the two entries without a code. */
internal fun WatchCity.label(name: String): String = if (code.isEmpty()) name else "$code $name"

/** "UTC+5", "UTC+5:45", "UTC-3:30", and "UTC" for zero. */
internal fun utcOffsetLabel(offsetMinutes: Int): String {
    if (offsetMinutes == 0) return "UTC"
    val sign = if (offsetMinutes < 0) '-' else '+'
    val hours = abs(offsetMinutes) / 60
    val minutes = abs(offsetMinutes) % 60
    return if (minutes == 0) "UTC$sign$hours" else "UTC$sign$hours:${TWO_DIGITS[minutes]}"
}

/** "Rio de Janeiro" from "RIO DE JANEIRO"; "UTC" stays as it is. */
internal fun titleCase(name: String): String =
    name.split(' ').joinToString(" ") { word ->
        when (word) {
            "UTC" -> word
            "DE" -> "de"
            else -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
