package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.noop.R
import com.noop.analytics.RouteMath
import com.noop.analytics.RunAnalysis
import com.noop.data.WorkoutRow
import com.noop.location.RunTrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Measured split timing stays separate from the untimed route and from heart-rate analysis. */
@Composable
internal fun RunDetailAnalysis(row: WorkoutRow) {
    val context = LocalContext.current
    val units = UnitPrefs.system(context)
    var track by remember(row.startTs, row.sport) { mutableStateOf<RunTrackStore.Track?>(null) }
    var loaded by remember(row.startTs, row.sport) { mutableStateOf(false) }
    var visibleCount by remember(row.startTs) { mutableStateOf(20) }
    LaunchedEffect(row.startTs, row.sport) {
        track = withContext(Dispatchers.IO) { RunTrackStore(context.applicationContext).load(row.startTs, row.sport) }
        loaded = true
    }
    val splits = remember(track, units) {
        RunAnalysis.splits(track?.samples ?: emptyList(), if (units == UnitSystem.IMPERIAL) 1609.344 else 1000.0)
    }
    CardDivider()
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(uiString(R.string.runs_average_pace), style = NoopType.subhead, color = Palette.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(UnitFormatter.paceFromSecPerKm(runPace(row, track), units), style = NoopType.headline, color = Palette.accent)
    }
    (row.routePolyline ?: track?.polyline)?.let { polyline ->
        val points = remember(polyline) { runCatching { RouteMath.decode(polyline) }.getOrDefault(emptyList()) }
        if (points.isNotEmpty()) {
            SectionHeader(title = uiString(R.string.runs_route))
            if (loaded) RouteCanvas(polyline, segmentStartIndices = track?.segmentStartIndices ?: emptyList())
            points.lastOrNull()?.let { point ->
                Text(uiString(R.string.runs_coordinates, RouteMath.formatCoordinates(point)),
                    style = NoopType.captionNumber, color = Palette.textSecondary)
            }
            Text(uiString(R.string.runs_offline_route), style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
    SectionHeader(title = uiString(R.string.runs_splits), trailing = UnitFormatter.distanceUnit(units))
    if (splits.isEmpty()) {
        Text(uiString(R.string.runs_splits_unavailable), style = NoopType.footnote, color = Palette.textTertiary)
    } else {
        Text(uiString(R.string.runs_pace_chart_unit, UnitFormatter.distanceUnit(units)), style = NoopType.overline, color = Palette.textSecondary)
        BarChart(splits.map { (if (units == UnitSystem.IMPERIAL) it.paceSecPerKm / UnitFormatter.MILES_PER_KILOMETER else it.paceSecPerKm) / 60 },
            Modifier.height(Metrics.compactChartHeight), color = Palette.effortColor)
        Text(uiString(R.string.runs_splits_note), style = NoopType.footnote, color = Palette.textTertiary)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(uiString(R.string.runs_split), style = NoopType.overline, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                Text(uiString(R.string.runs_active_time), style = NoopType.overline, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                Text(uiString(R.string.l10n_live_screen_pace_7a9a6226), style = NoopType.overline, color = Palette.textSecondary, modifier = Modifier.weight(1f))
            }
            splits.take(visibleCount).forEach { split ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (split.isPartial) uiString(R.string.runs_partial_split, split.index) else split.index.toString(),
                            style = NoopType.subhead, color = Palette.textSecondary)
                        if (split.isPartial) Text(UnitFormatter.distanceFromMeters(split.distanceM, units),
                            style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Text(runDuration(split.durationS), style = NoopType.captionNumber, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    Text(UnitFormatter.paceFromSecPerKm(split.paceSecPerKm, units), style = NoopType.captionNumber,
                        color = Palette.effortColor, modifier = Modifier.weight(1f))
                }
            }
            if (splits.size > visibleCount) NoopButton(text = uiString(R.string.runs_show_more), kind = NoopButtonKind.Secondary,
                onClick = { visibleCount += 20 })
        }
    }
}
