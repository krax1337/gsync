package io.github.krax1337.gsync.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.krax1337.gsync.protocol.Alarm
import io.github.krax1337.gsync.protocol.AlarmSet
import io.github.krax1337.gsync.watch.WatchActions
import io.github.krax1337.gsync.watch.WatchState
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Start values offered as chips, in seconds. */
private val TIMER_PRESETS = listOf(60, 180, 300, 600, 900, 1800, 3600)

/** The watch counts down from 1 second to 24 hours. */
private const val TIMER_MAX_SECONDS = 24 * 60 * 60

/** The last alarm is the watch's snooze alarm. */
private const val SNOOZE_INDEX = AlarmSet.COUNT - 1
private const val SNOOZE_NOTE = "repeats every 5 min, up to 7 times"

@Composable
internal fun AlarmsScreen(state: WatchState, actions: WatchActions, padding: PaddingValues) {
    val alarms = state.alarms
    val timer = state.timerSeconds
    // Times read the way the watch shows them; the phone setting stands in until that is known.
    val is24Hour = state.settings?.use24Hour ?: DateFormat.is24HourFormat(LocalContext.current)
    val nowMinute = rememberTicks(offsetMs = state.clock.offsetMs, unitMs = MINUTE_MS)
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var customTimer by rememberSaveable { mutableStateOf(false) }

    ScreenColumn(padding) {
        ScreenTitle("Alarms")
        if (alarms == null || timer == null) {
            Gap()
            EmptyCard(
                title = "Connect the watch once to load its alarms and timer",
                text = "Hold the lower-left button on the watch for about 3 seconds, until CONNECT appears.",
            )
        }
        if (alarms != null) {
            Gap()
            Panel {
                alarms.alarms.forEachIndexed { index, alarm ->
                    key(index) {
                        if (index > 0) RowDivider()
                        AlarmRow(
                            label = alarmLabel(index),
                            enabledNote = SNOOZE_NOTE.takeIf { index == SNOOZE_INDEX },
                            alarm = alarm,
                            is24Hour = is24Hour,
                            nowMinute = nowMinute,
                            onEdit = { editing = index },
                            onToggle = { actions.setAlarms(alarms.with(index, alarm.copy(enabled = it))) },
                        )
                    }
                }
            }
            Gap()
            Panel {
                SwitchRow(
                    title = "Hourly time signal",
                    supporting = "A short beep on every hour",
                    checked = alarms.hourlyChime,
                    onCheckedChange = { actions.setAlarms(alarms.copy(hourlyChime = it)) },
                )
            }
        }
        if (timer != null) {
            Gap()
            TimerSection(seconds = timer, onSet = actions::setTimer, onCustom = { customTimer = true })
        }
        Reveal(visible = state.pendingWrites) { PendingBanner() }
    }

    val index = editing
    if (alarms != null && index != null) {
        AlarmTimeDialog(
            title = alarmLabel(index),
            alarm = alarms.alarms[index],
            is24Hour = is24Hour,
            onConfirm = { hour, minute ->
                actions.setAlarms(alarms.with(index, Alarm(hour = hour, minute = minute, enabled = true)))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    if (timer != null && customTimer) {
        TimerDialog(
            initial = timer,
            onConfirm = {
                actions.setTimer(it)
                customTimer = false
            },
            onDismiss = { customTimer = false },
        )
    }
}

/**
 * Two touch targets side by side: the time opens the picker, the end of the row is the switch.
 * [nowMinute] is read here, so the minute tick redraws the rows and not the screen.
 * [enabledNote] follows the time left on the second line while the alarm is on.
 */
@Composable
private fun AlarmRow(
    label: String,
    enabledNote: String?,
    alarm: Alarm,
    is24Hour: Boolean,
    nowMinute: LongState,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val timeColor by animateColorAsState(
        targetValue = if (alarm.enabled) scheme.onSurface else scheme.onSurfaceVariant,
        animationSpec = tween(MOTION_MS),
        label = "alarm time",
    )
    val minute = nowMinute.longValue
    val status = remember(alarm, minute, enabledNote) {
        if (alarm.enabled) {
            listOfNotNull(ringsIn(alarm, minute), enabledNote).joinToString(" · ").ifEmpty { null }
        } else {
            "Off"
        }
    }

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClickLabel = "Change time", onClick = onEdit)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row {
                Text(
                    text = clockTime(alarm, is24Hour),
                    style = MaterialTheme.typography.headlineLarge,
                    color = timeColor,
                    modifier = Modifier.alignByBaseline(),
                )
                if (!is24Hour) {
                    Text(
                        text = if (alarm.hour < 12) "AM" else "PM",
                        style = MaterialTheme.typography.titleMedium,
                        color = timeColor,
                        modifier = Modifier.alignByBaseline().padding(start = 4.dp),
                    )
                }
            }
            Text(
                text = if (status == null) label else "$label · $status",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .toggleable(
                    value = alarm.enabled,
                    role = Role.Switch,
                    onValueChange = {
                        haptics.toggle(it)
                        onToggle(it)
                    },
                )
                .semantics { contentDescription = label }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Switch(checked = alarm.enabled, onCheckedChange = null)
        }
    }
}

@Composable
private fun TimerSection(seconds: Int, onSet: (Int) -> Unit, onCustom: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Section("Timer") {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Text(
                text = timerLabel(seconds),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { contentDescription = timerWords(seconds) },
            )
            Text(
                text = "The countdown on the watch starts from here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "The watch does not sync while the timer is counting down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TIMER_PRESETS.forEach { preset ->
                    key(preset) {
                        FilterChip(
                            selected = preset == seconds,
                            onClick = {
                                haptics.tick()
                                if (preset != seconds) onSet(preset)
                            },
                            label = { Text(timerWords(preset)) },
                        )
                    }
                }
                FilterChip(
                    selected = seconds !in TIMER_PRESETS,
                    onClick = {
                        haptics.tick()
                        onCustom()
                    },
                    label = { Text("Custom") },
                )
            }
        }
    }
}

@Composable
private fun TimerDialog(initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var hours by rememberSaveable { mutableStateOf((initial / 3600).toString()) }
    var minutes by rememberSaveable { mutableStateOf((initial % 3600 / 60).toString()) }
    var seconds by rememberSaveable { mutableStateOf((initial % 60).toString()) }
    val h = fieldValue(hours, max = 24)
    val m = fieldValue(minutes, max = 59)
    val s = fieldValue(seconds, max = 59)
    val total = if (h != null && m != null && s != null) {
        (h * 3600 + m * 60 + s).takeIf { it in 1..TIMER_MAX_SECONDS }
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val field = Modifier.weight(1f)
                    TimerField(
                        value = hours,
                        onValueChange = { hours = it },
                        label = "Hours",
                        name = "Hours",
                        isError = h == null,
                        modifier = field,
                    )
                    TimerField(
                        value = minutes,
                        onValueChange = { minutes = it },
                        label = "Min",
                        name = "Minutes",
                        isError = m == null,
                        modifier = field,
                    )
                    TimerField(
                        value = seconds,
                        onValueChange = { seconds = it },
                        label = "Sec",
                        name = "Seconds",
                        isError = s == null,
                        modifier = field,
                    )
                }
                Text(
                    text = "From 1 second to 24 hours",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.run { if (total == null) error else onSurfaceVariant },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (total != null) {
                        haptics.confirm()
                        onConfirm(total)
                    }
                },
                enabled = total != null,
            ) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Two-digit number field. Three of them share a dialog, so the printed [label] is kept short;
 * [name] is what the screen reader calls the field.
 */
@Composable
private fun TimerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    name: String,
    isError: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.length <= 2 && input.all(Char::isDigit)) onValueChange(input) },
        modifier = modifier.semantics { contentDescription = name },
        label = { Text(text = label, maxLines = 1) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/**
 * Sized to its content rather than to the platform dialog width, which is too narrow for the
 * clock dial on a 360dp screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTimeDialog(
    title: String,
    alarm: Alarm,
    is24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val picker = rememberTimePickerState(initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = is24Hour)
    // The dial does not fit a landscape phone; fall back to the keyboard fields there.
    val showDial = LocalConfiguration.current.screenHeightDp >= 480

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.width(IntrinsicSize.Min).height(IntrinsicSize.Min),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
                if (showDial) TimePicker(state = picker) else TimeInput(state = picker)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            haptics.confirm()
                            onConfirm(picker.hour, picker.minute)
                        },
                    ) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

private fun alarmLabel(index: Int): String = if (index == SNOOZE_INDEX) "Snooze alarm" else "Alarm ${index + 1}"

private fun AlarmSet.with(index: Int, alarm: Alarm): AlarmSet =
    copy(alarms = alarms.mapIndexed { i, current -> if (i == index) alarm else current })

/** "06:30" in 24-hour mode, "6:30" (AM/PM is drawn separately) otherwise. */
private fun clockTime(alarm: Alarm, is24Hour: Boolean): String =
    if (is24Hour) {
        "%02d:%02d".format(Locale.ROOT, alarm.hour, alarm.minute)
    } else {
        "%d:%02d".format(Locale.ROOT, (alarm.hour + 11) % 12 + 1, alarm.minute)
    }

/**
 * "in 7 h 12 min": from the reference minute [epochMinute] to the next time the clock reads the
 * alarm time. Null for a time the watch could not ring at.
 */
private fun ringsIn(alarm: Alarm, epochMinute: Long): String? {
    if (alarm.hour !in 0..23 || alarm.minute !in 0..59) return null
    val now = Instant.ofEpochSecond(epochMinute * 60).atZone(ZoneId.systemDefault())
    var next = now.with(LocalTime.of(alarm.hour, alarm.minute))
    if (!next.isAfter(now)) next = next.plusDays(1)
    val minutes = Duration.between(now, next).toMinutes()
    return when {
        minutes < 60 -> "in $minutes min"
        minutes % 60 == 0L -> "in ${minutes / 60} h"
        else -> "in ${minutes / 60} h ${minutes % 60} min"
    }
}

/** "10:00" under an hour, "1:30:00" from there on. */
private fun timerLabel(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val rest = "${TWO_DIGITS[total % 3600 / 60]}:${TWO_DIGITS[total % 60]}"
    return if (total < 3600) rest else "${total / 3600}:$rest"
}

/** "10 min", "1 h", "1 h 5 min 30 s". */
private fun timerWords(seconds: Int): String =
    listOfNotNull(
        (seconds / 3600).takeIf { it > 0 }?.let { "$it h" },
        (seconds % 3600 / 60).takeIf { it > 0 }?.let { "$it min" },
        (seconds % 60).takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ")

/** The number in a dialog field, an empty field counting as zero; null when it is out of range. */
private fun fieldValue(text: String, max: Int): Int? =
    if (text.isEmpty()) 0 else text.toIntOrNull()?.takeIf { it in 0..max }
