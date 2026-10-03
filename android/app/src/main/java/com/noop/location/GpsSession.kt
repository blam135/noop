package com.noop.location

import android.content.Context
import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import com.noop.analytics.RunAnalysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-owned GPS recording, driven by the foreground service and checkpointed on device. */
object GpsSession {
    const val MAX_POINTS = 50_000
    data class State(
        val active: Boolean = false,
        val startMs: Long = 0L,
        val sportName: String = "",
        val track: List<LatLng> = emptyList(),
        val distanceM: Double = 0.0,
        val paceSecPerKm: Double? = null,
        val paused: Boolean = false,
        val pausedAtMs: Long? = null,
        val pausedDurationMs: Long = 0L,
        val samples: List<RunAnalysis.Sample> = emptyList(),
        val originActiveMs: Long? = null,
        val lastFixMs: Long = 0L,
        val unavailable: Boolean = false,
        val segmentStartIndices: List<Int> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    var workoutsLog: ((String) -> Unit)? = null
    private var previousPoint: LatLng? = null
    private var minimumFixMs: Long = 0L
    private var checkpoint: GpsCheckpointStore? = null
    private var lastCheckpointMs = 0L

    /** Called once by the Application. Restore route/time without joining an unrecorded gap. */
    fun initialize(context: Context) {
        if (checkpoint != null) return
        checkpoint = GpsCheckpointStore(context)
        checkpoint?.load()?.let { restore(it) }
    }

    internal fun restore(snapshot: State, nowMs: Long = System.currentTimeMillis()) {
        _state.value = snapshot
        previousPoint = null
        minimumFixMs = nowMs
        lastCheckpointMs = 0
    }

    fun start(startMs: Long, sportName: String) {
        if (_state.value.active) return
        previousPoint = null
        minimumFixMs = startMs
        _state.value = State(active = true, startMs = startMs, sportName = sportName)
        persist(force = true)
    }

    /** Fold measured fixes in constant distance work. A pause/restore clears continuity. */
    fun append(fix: AcceptedFix) {
        val s = _state.value
        val pt = fix.point
        if (!s.active || s.paused || fix.tMs < maxOf(s.startMs, minimumFixMs) ||
            (s.lastFixMs > 0 && fix.tMs <= s.lastFixMs) ||
            !pt.lat.isFinite() || !pt.lon.isFinite() || pt.lat !in -90.0..90.0 || pt.lon !in -180.0..180.0) return
        val activeMs = (fix.tMs - s.startMs - s.pausedDurationMs).coerceAtLeast(0)
        val origin = s.originActiveMs ?: activeMs
        val elapsed = ((activeMs - origin).coerceAtLeast(0)) / 1000.0
        val dist = s.distanceM + (previousPoint?.let { RouteMath.haversineMeters(it, pt) } ?: 0.0)
        val sample = RunAnalysis.Sample(dist, elapsed)
        val timed = when {
            s.samples.isEmpty() -> listOf(sample)
            elapsed > s.samples.last().elapsedS -> compact(s.samples) + sample
            else -> s.samples
        }
        val (retained, retainedBreaks) = compactTrack(s.track, s.segmentStartIndices)
        val breaks = if (previousPoint == null && retained.isNotEmpty()) retainedBreaks + retained.size else retainedBreaks
        val track = retained + pt
        previousPoint = pt
        _state.value = s.copy(track = track, distanceM = dist, samples = timed,
            originActiveMs = origin, lastFixMs = fix.tMs, unavailable = false,
            segmentStartIndices = breaks, paceSecPerKm = RouteMath.paceSecPerKm(dist, elapsed))
        persist(nowMs = fix.tMs)
        workoutsLog?.invoke(com.noop.analytics.WorkoutsTrace.gpsLine(
            rawFixes = null, acceptedPoints = track.size, distanceM = dist))
    }

    /** Mark a provider/permission failure visibly, while retaining recorded data. */
    fun markUnavailable(nowMs: Long = System.currentTimeMillis()) {
        if (_state.value.active) {
            _state.value = _state.value.copy(unavailable = true)
            previousPoint = null
            minimumFixMs = nowMs
        }
    }

    /** Ignore cached platform readings from before the latest resume/process restoration. */
    fun minimumFixTime(): Long = minimumFixMs

    fun stop(): List<LatLng> {
        val track = _state.value.track
        _state.value = State()
        previousPoint = null
        checkpoint?.save(null)
        return track
    }

    fun pause(nowMs: Long = System.currentTimeMillis()) {
        val s = _state.value
        if (s.active && !s.paused) {
            _state.value = s.copy(paused = true, pausedAtMs = nowMs)
            previousPoint = null
            minimumFixMs = nowMs
            persist(force = true)
        }
    }

    fun resume(nowMs: Long = System.currentTimeMillis()) {
        val s = _state.value
        if (s.active && s.paused) {
            val added = s.pausedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L
            _state.value = s.copy(paused = false, pausedAtMs = null,
                pausedDurationMs = s.pausedDurationMs + added, unavailable = false)
            previousPoint = null
            minimumFixMs = nowMs
            persist(force = true)
        }
    }

    private fun persist(force: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        if (force || nowMs - lastCheckpointMs >= 10_000) {
            lastCheckpointMs = nowMs
            checkpoint?.save(_state.value)
        }
    }

    /** Keep the first/last measurement when long recordings need downsampling. */
    private fun <T> compact(points: List<T>): List<T> =
        if (points.size < MAX_POINTS) points else points.filterIndexed { index, _ ->
            index % 2 == 0 || index == points.lastIndex
        }

    private fun compactTrack(points: List<LatLng>, breaks: List<Int>): Pair<List<LatLng>, List<Int>> {
        if (points.size < MAX_POINTS) return points to breaks
        val edges = breaks.flatMap { listOf(it - 1, it) }.toSet()
        val kept = points.indices.filter { it % 2 == 0 || it == points.lastIndex || it in edges }
        val positions = kept.withIndex().associate { it.value to it.index }
        return kept.map { points[it] } to breaks.mapNotNull { positions[it] }
    }
}
