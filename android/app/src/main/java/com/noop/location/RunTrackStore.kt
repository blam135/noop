package com.noop.location

import android.content.Context
import android.util.AtomicFile
import com.noop.analytics.RouteMath.LatLng
import com.noop.analytics.RunAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Optional local timing companion to an existing workout row; old rows remain readable. */
class RunTrackStore internal constructor(
    private val directory: File,
    private val readFile: (File) -> String = { AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() } },
    private val writeFile: (File, String) -> Unit = ::atomicWrite,
    private val deleteFile: (File) -> Unit = { AtomicFile(it).delete() },
) {
    constructor(context: Context) : this(File(context.filesDir, "run-tracks"))
    data class Key(val startTs: Long, val sport: String)
    data class Track(val samples: List<RunAnalysis.Sample>, val segmentStartIndices: List<Int> = emptyList(), val polyline: String? = null)

    fun load(startTs: Long, sport: String): Track? = runCatching {
        val file = path(startTs, sport)
        if (file.length() > MAX_BYTES || File(file.path + ".bak").length() > MAX_BYTES) return null
        val json = JSONObject(readFile(file))
        if (json.optLong("startTs") != startTs || json.optString("sport") != sport) return null
        RunTrackCodec.track(json)
    }.getOrNull()

    fun save(startTs: Long, sport: String, samples: List<RunAnalysis.Sample>, segmentStartIndices: List<Int>, polyline: String? = null): Boolean {
        return runCatching {
            if ((samples.isEmpty() && polyline.isNullOrEmpty()) || samples.size > GpsSession.MAX_POINTS) return@runCatching false
            directory.mkdirs()
            val json = JSONObject().put("startTs", startTs).put("sport", sport)
                .put("samples", RunTrackCodec.encodeSamples(samples))
                .put("segmentStartIndices", JSONArray(segmentStartIndices))
                .put("polyline", polyline ?: JSONObject.NULL)
            writeFile(path(startTs, sport), json.toString())
            directory.listFiles()?.filter { it.extension == "json" }
                ?.sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: 0L }
                ?.drop(400)?.forEach { deleteFile(it) }
            true
        }.getOrDefault(false)
    }

    /** Move timing and gap geometry alongside a workout's edited natural key. */
    fun rekey(from: Key, to: Key) {
        if (from == to) return
        val track = load(from.startTs, from.sport) ?: return
        if (save(to.startTs, to.sport, track.samples, track.segmentStartIndices, track.polyline)) remove(from)
    }

    fun remove(key: Key) { runCatching { deleteFile(path(key.startTs, key.sport)) } }

    /** Merged activities keep representative geometry; one constituent's timing cannot describe all. */
    fun consolidate(keys: List<Key>, target: Key, representative: Track?) {
        keys.forEach { remove(it) }
        representative?.polyline?.let { polyline ->
            save(target.startTs, target.sport, emptyList(), representative.segmentStartIndices, polyline)
        }
    }

    private fun path(startTs: Long, sport: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(sport.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$startTs-$digest.json")
    }
    companion object { internal const val MAX_BYTES = 12_000_000L }
}

/** Conflated off-main-thread checkpoints; clearing is serialized after prior saves. */
internal class GpsCheckpointStore(context: Context) {
    private val file = File(context.filesDir, "active-gps-workout.json")
    private data class Pending(val state: GpsSession.State?)
    private val writes = Channel<Pending>(Channel.CONFLATED)
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (pending in writes) runCatching {
                if (pending.state == null) AtomicFile(file).delete()
                else atomicWrite(file, GpsCheckpointCodec.encode(pending.state))
            }
        }
    }
    fun save(snapshot: GpsSession.State?) { writes.trySend(Pending(snapshot)) }
    fun load(): GpsSession.State? = runCatching {
        if (file.length() > RunTrackStore.MAX_BYTES || File(file.path + ".bak").length() > RunTrackStore.MAX_BYTES) null
        else GpsCheckpointCodec.decode(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
}

internal object RunTrackCodec {
    /** Geometry retains explicit segment metadata even when a merged activity has no split timing. */
    fun track(json: JSONObject): RunTrackStore.Track {
        val samples = samples(json.optJSONArray("samples"))
        val breaks = indices(json.optJSONArray("segmentStartIndices"))
        var polyline = if (json.isNull("polyline")) null else json.optString("polyline").takeIf { it.isNotEmpty() && it.length <= 1_000_000 }
        if (polyline != null) {
            val encoded = polyline
            val points = runCatching { com.noop.analytics.RouteMath.decode(encoded) }.getOrDefault(emptyList())
            val rawBreaks = json.optJSONArray("segmentStartIndices")
            val validBreaks = rawBreaks != null && rawBreaks.length() == breaks.size &&
                breaks.all { it in 1 until points.size }
            if (!validBreaks || points.size < 2 || points.size > GpsSession.MAX_POINTS || points.any {
                    !it.lat.isFinite() || !it.lon.isFinite() || it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0
                }) polyline = null
        }
        return RunTrackStore.Track(samples, breaks, polyline)
    }

    fun encodeSamples(samples: List<RunAnalysis.Sample>): JSONArray = JSONArray().apply {
        samples.forEach { put(JSONObject().put("distanceM", it.distanceM).put("elapsedS", it.elapsedS)) }
    }
    fun samples(array: JSONArray?): List<RunAnalysis.Sample> {
        if (array == null || array.length() > GpsSession.MAX_POINTS) return emptyList()
        val result = ArrayList<RunAnalysis.Sample>()
        for (i in 0 until array.length()) {
            val value = array.optJSONObject(i) ?: return emptyList()
            val distance = (value.opt("distanceM") as? Number)?.toDouble() ?: return emptyList()
            val time = (value.opt("elapsedS") as? Number)?.toDouble() ?: return emptyList()
            if (!distance.isFinite() || distance < 0 || !time.isFinite() || time < 0) return emptyList()
            val last = result.lastOrNull()
            if (last != null && (distance < last.distanceM || time <= last.elapsedS)) return emptyList()
            result += RunAnalysis.Sample(distance, time)
        }
        return result
    }
    fun indices(array: JSONArray?): List<Int> {
        if (array == null || array.length() > GpsSession.MAX_POINTS) return emptyList()
        return (0 until array.length()).mapNotNull {
            val number = (array.opt(it) as? Number)?.toDouble() ?: return@mapNotNull null
            number.takeIf { value -> value.isFinite() && value > 0 && value <= GpsSession.MAX_POINTS && value % 1.0 == 0.0 }?.toInt()
        }
            .distinct().sorted()
    }
}

/** Pure JSON codec shared by checkpoint IO and JVM corruption/round-trip tests. */
internal object GpsCheckpointCodec {
    fun encode(s: GpsSession.State): String = JSONObject()
        .put("startMs", s.startMs).put("sportName", s.sportName).put("distanceM", s.distanceM)
        .put("paused", s.paused).put("pausedAtMs", s.pausedAtMs ?: JSONObject.NULL)
        .put("pausedDurationMs", s.pausedDurationMs).put("originActiveMs", s.originActiveMs ?: JSONObject.NULL)
        .put("lastFixMs", s.lastFixMs)
        .put("track", JSONArray().apply { s.track.forEach { put(JSONArray(listOf(it.lat, it.lon))) } })
        .put("samples", RunTrackCodec.encodeSamples(s.samples))
        .put("segmentStartIndices", JSONArray(s.segmentStartIndices)).toString()

    fun decode(raw: String): GpsSession.State? = runCatching {
        if (raw.length > RunTrackStore.MAX_BYTES) return null
        val json = JSONObject(raw)
        val startMs = json.getLong("startMs")
        val sport = json.getString("sportName")
        val distance = json.getDouble("distanceM")
        val pauses = json.optLong("pausedDurationMs", 0L)
        if (startMs <= 0 || sport.isBlank() || sport.length > 200 || !distance.isFinite() || distance < 0 || pauses < 0) return null
        val array = json.getJSONArray("track")
        if (array.length() > GpsSession.MAX_POINTS) return null
        val track = (0 until array.length()).map { index ->
            val point = array.getJSONArray(index)
            val lat = point.getDouble(0)
            val lon = point.getDouble(1)
            require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
            LatLng(lat, lon)
        }
        val samples = RunTrackCodec.samples(json.optJSONArray("samples"))
        if (track.isNotEmpty() && samples.isEmpty()) return null
        val origin = json.optLong("originActiveMs", -1L).takeIf { it >= 0 }
        val lastFix = json.optLong("lastFixMs", 0L)
        val pausedAt = json.optLong("pausedAtMs", 0L).takeIf { it >= startMs }
        val paused = json.optBoolean("paused")
        if (paused && pausedAt == null) return null
        GpsSession.State(active = true, startMs = startMs, sportName = sport, track = track,
            distanceM = distance, paused = paused, pausedAtMs = if (paused) pausedAt else null,
            pausedDurationMs = pauses, samples = samples, originActiveMs = origin, lastFixMs = lastFix,
            segmentStartIndices = RunTrackCodec.indices(json.optJSONArray("segmentStartIndices"))
                .filter { it < track.size })
    }.getOrNull()
}

private fun atomicWrite(file: File, raw: String) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(raw.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}
