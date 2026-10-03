package com.noop.analytics

/** Distance-weighted run summaries and splits from measured distance/active-time samples. */
object RunAnalysis {
    data class Session(val startTs: Long, val distanceM: Double?, val durationS: Double?, val paceDurationS: Double? = null)
    data class Summary(
        val count: Int, val totalDistanceM: Double, val totalDurationS: Double,
        val paceSecPerKm: Double?, val longestDistanceM: Double,
    )
    data class Sample(val distanceM: Double, val elapsedS: Double)
    data class Split(
        val index: Int, val distanceM: Double, val durationS: Double,
        val paceSecPerKm: Double, val isPartial: Boolean,
    )
    private val runningNames = setOf("run", "running", "outdoorrun", "outdoorrunning", "trailrun", "trailrunning",
        "treadmill", "treadmillrun", "treadmillrunning", "indoorrun", "indoorrunning")

    fun isRunningSport(sport: String): Boolean {
        val key = sport.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        return key in runningNames
    }

    fun summary(sessions: List<Session>): Summary {
        var distance = 0.0
        var duration = 0.0
        var paceDistance = 0.0
        var paceDuration = 0.0
        var longest = 0.0
        for (session in sessions) {
            val meters = session.distanceM?.takeIf { it.isFinite() && it > 0 }
            val seconds = session.durationS?.takeIf { it.isFinite() && it > 0 }
            val paceSeconds = (session.paceDurationS ?: session.durationS)?.takeIf { it.isFinite() && it > 0 }
            if (meters != null) { distance += meters; longest = maxOf(longest, meters) }
            if (seconds != null) duration += seconds
            if (meters != null && paceSeconds != null) { paceDistance += meters; paceDuration += paceSeconds }
        }
        return Summary(sessions.size, distance, duration,
            if (paceDistance > 0) paceDuration / (paceDistance / 1000) else null, longest)
    }

    /** Interpolate actual GPS crossing times; an untimed route never supplies split timing. */
    fun splits(samples: List<Sample>, unitMeters: Double = 1000.0): List<Split> {
        if (!unitMeters.isFinite() || unitMeters <= 0) return emptyList()
        val points = ArrayList<Sample>()
        for (sample in samples) {
            if (!sample.distanceM.isFinite() || !sample.elapsedS.isFinite() ||
                sample.distanceM < 0 || sample.elapsedS < 0) continue
            val last = points.lastOrNull()
            if (last != null && (sample.distanceM < last.distanceM || sample.elapsedS <= last.elapsedS)) continue
            points += sample
        }
        if (points.size < 2 || points.first().distanceM != 0.0 || points.last().distanceM <= 0) return emptyList()
        val result = ArrayList<Split>()
        var nextDistance = unitMeters
        var previousDistance = 0.0
        var previousTime = points.first().elapsedS
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            if (b.distanceM <= a.distanceM) continue
            while (nextDistance <= b.distanceM && result.size < 2000) {
                val fraction = (nextDistance - a.distanceM) / (b.distanceM - a.distanceM)
                val crossing = a.elapsedS + fraction * (b.elapsedS - a.elapsedS)
                val seconds = crossing - previousTime
                val meters = nextDistance - previousDistance
                if (seconds <= 0 || !seconds.isFinite()) return result
                result += Split(result.size + 1, meters, seconds, seconds / (meters / 1000), false)
                previousDistance = nextDistance
                previousTime = crossing
                nextDistance += unitMeters
            }
            if (result.size >= 2000) return result
        }
        val remaining = points.last().distanceM - previousDistance
        val seconds = points.last().elapsedS - previousTime
        if (remaining > 0.01 && seconds > 0 && seconds.isFinite()) {
            result += Split(result.size + 1, remaining, seconds, seconds / (remaining / 1000), true)
        }
        return result
    }
}
