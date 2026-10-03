package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.RunAnalysis
import com.noop.analytics.WorkoutSport
import com.noop.data.WorkoutRow
import com.noop.location.RunTrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** An on-device running hub, using the same live recorder and saved activity detail as Workouts. */
@Composable
fun RunsScreen(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val allRows by vm.workouts.collectAsStateWithLifecycle()
    val activeWorkout by vm.activeWorkout.collectAsStateWithLifecycle()
    val units = UnitPrefs.system(context)
    var days by remember { mutableStateOf(30) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var showLive by remember { mutableStateOf(false) }
    var selectedRow by remember { mutableStateOf<WorkoutRow?>(null) }
    var visibleCount by remember(days) { mutableStateOf(20) }
    var tracks by remember { mutableStateOf<Map<String, RunTrackStore.Track>>(emptyMap()) }
    val runningRows = remember(allRows) { allRows.filter { RunAnalysis.isRunningSport(it.sport) } }
    LaunchedEffect(Unit) {
        vm.loadWorkouts()
        while (true) { today = LocalDate.now(); delay(60_000) }
    }
    LaunchedEffect(runningRows) {
        tracks = withContext(Dispatchers.IO) {
            val store = RunTrackStore(context.applicationContext)
            runningRows.mapNotNull { row -> store.load(row.startTs, row.sport)?.let { runKey(row) to it } }.toMap()
        }
    }
    val zone = ZoneId.systemDefault()
    val since = today.minusDays(days.toLong() - 1).atStartOfDay(zone).toEpochSecond()
    val until = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val rows = runningRows.filter { it.startTs in since until until }.sortedByDescending { it.startTs }
    val summary = RunAnalysis.summary(rows.map { row ->
        RunAnalysis.Session(row.startTs, row.distanceM, row.durationS,
            tracks[runKey(row)]?.samples?.lastOrNull()?.elapsedS)
    })
    val startRun = rememberRequestLocation { granted ->
        if (activeWorkout == null) {
            val running = WorkoutSport.all.first { it.name == "Running" }
            vm.startWorkout(running, gpsEnabled = granted, gpsRequested = true)
        }
        showLive = true
    }

    LazyScreenScaffold(
        title = uiString(R.string.runs_title), subtitle = uiString(R.string.runs_subtitle),
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        trailing = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, uiString(R.string.runs_close), tint = Palette.textSecondary)
            }
        },
    ) {
        item {
            NoopButton(
                text = uiString(if (activeWorkout == null) R.string.runs_start else R.string.runs_open_workout),
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (activeWorkout == null) startRun() else showLive = true },
            )
        }
        item {
            SegmentedPillControl(listOf(7, 30, 90), days,
                label = { uiString(R.string.runs_range_days, it) }, onSelect = { days = it },
                modifier = Modifier.fillMaxWidth(), adaptsToAvailableWidth = true)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    StatTile(uiString(R.string.runs_count), summary.count.toString(), modifier = Modifier.weight(1f))
                    StatTile(uiString(R.string.runs_total_distance), if (summary.totalDistanceM > 0) UnitFormatter.distanceFromMeters(summary.totalDistanceM, units) else "—", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    StatTile(uiString(R.string.runs_active_time), runDuration(summary.totalDurationS), modifier = Modifier.weight(1f))
                    StatTile(uiString(R.string.runs_average_pace), UnitFormatter.paceFromSecPerKm(summary.paceSecPerKm, units), modifier = Modifier.weight(1f))
                }
                NoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(uiString(R.string.runs_longest), style = NoopType.subhead, color = Palette.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(if (summary.longestDistanceM > 0) UnitFormatter.distanceFromMeters(summary.longestDistanceM, units) else "—", style = NoopType.headline, color = Palette.accent)
                    }
                }
                Text(uiString(R.string.runs_summary_note), style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
        if (rows.isEmpty()) {
            item {
                NoopCard {
                    Text(uiString(R.string.runs_empty), style = NoopType.body, color = Palette.textSecondary)
                }
            }
        } else {
            item { WeeklyRunDistance(rows, today.minusDays(days.toLong() - 1), today, units) }
            item { SectionHeader(title = uiString(R.string.runs_recent)) }
            rows.take(visibleCount).forEach { row ->
                item(key = "run-${row.deviceId}-${row.startTs}-${row.sport}") {
                    NoopCard(modifier = Modifier.clickable { selectedRow = row }) {
                        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(runDate(row.startTs), style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                                Text(WorkoutEditing.displaySport(row.sport), style = NoopType.footnote, color = Palette.textSecondary)
                            }
                            Text(
                                listOf(row.distanceM?.let { UnitFormatter.distanceFromMeters(it, units) } ?: "—",
                                    runDuration(row.durationS), UnitFormatter.paceFromSecPerKm(runPace(row, tracks[runKey(row)]), units)).joinToString(" · "),
                                style = NoopType.captionNumber, color = Palette.accent,
                            )
                        }
                    }
                }
            }
            if (rows.size > visibleCount) item {
                NoopButton(text = uiString(R.string.runs_show_more), kind = NoopButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(), onClick = { visibleCount += 20 })
            }
        }
    }
    selectedRow?.let { WorkoutDetailSheet(vm, it) { selectedRow = null } }
    if (showLive && activeWorkout != null) {
        Dialog(onDismissRequest = { showLive = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LiveWorkoutScreen(vm) { showLive = false }
        }
    }
    LaunchedEffect(activeWorkout) { if (activeWorkout == null) showLive = false }
}

@Composable
private fun WeeklyRunDistance(rows: List<WorkoutRow>, since: LocalDate, today: LocalDate, units: UnitSystem) {
    val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val firstWeek = since.with(TemporalAdjusters.previousOrSame(firstDay))
    val lastWeek = today.with(TemporalAdjusters.previousOrSame(firstDay))
    val weeks = generateSequence(firstWeek) { it.plusWeeks(1) }.takeWhile { !it.isAfter(lastWeek) }.toList()
    val totals = weeks.map { week -> rows.sumOf { row ->
        val day = Instant.ofEpochSecond(row.startTs).atZone(ZoneId.systemDefault()).toLocalDate()
        if (!day.isBefore(week) && day.isBefore(week.plusWeeks(1))) row.distanceM?.takeIf { it.isFinite() && it > 0 } ?: 0.0 else 0.0
    } }
    val values = totals.map { if (units == UnitSystem.IMPERIAL) UnitFormatter.kmToMiles(it / 1000) else it / 1000 }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(title = uiString(R.string.runs_weekly_distance), trailing = UnitFormatter.distanceUnit(units))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                BarChart(values, Modifier.height(Metrics.compactChartHeight), color = Palette.effortColor,
                    selectionEnabled = true, selectionLabels = weeks.map { uiString(R.string.runs_week_start, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))) })
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(weeks.first().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)), style = NoopType.footnote, color = Palette.textTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(weeks.last().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)), style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }
    }
}

internal fun runKey(row: WorkoutRow): String = "${row.startTs}|${row.sport}"
internal fun runPace(row: WorkoutRow, track: RunTrackStore.Track?): Double? {
    val meters = row.distanceM?.takeIf { it.isFinite() && it > 0 } ?: return null
    val seconds = (track?.samples?.lastOrNull()?.elapsedS ?: row.durationS)?.takeIf { it.isFinite() && it > 0 } ?: return null
    return seconds / (meters / 1000)
}
internal fun runDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0) return "—"
    val whole = seconds.toLong()
    return if (whole >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", whole / 3600, whole / 60 % 60, whole % 60)
    else String.format(Locale.getDefault(), "%d:%02d", whole / 60, whole % 60)
}
private fun runDate(ts: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))
