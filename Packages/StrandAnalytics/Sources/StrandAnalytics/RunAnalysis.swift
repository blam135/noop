import Foundation

/// Distance-weighted run summaries and GPS splits. Splits interpolate crossing times between
/// recorded distance/time samples; they never derive timing from an untimed route polyline.
public enum RunAnalysis {
    public struct Session: Equatable, Sendable {
        public let startTs: Int
        public let distanceM: Double?
        public let durationS: Double?
        public let paceDurationS: Double?

        public init(startTs: Int, distanceM: Double?, durationS: Double?, paceDurationS: Double? = nil) {
            self.startTs = startTs
            self.distanceM = distanceM
            self.durationS = durationS
            self.paceDurationS = paceDurationS ?? durationS
        }
    }

    public struct Summary: Equatable, Sendable {
        public let count: Int
        public let totalDistanceM: Double
        public let totalDurationS: Double
        public let paceSecPerKm: Double?
        public let longestDistanceM: Double
    }

    /// Canonical stored sport labels, including legacy imports. Exact matching avoids admitting
    /// unrelated activities such as running a strength circuit. Labels are never localized on disk.
    public static func isRunningSport(_ sport: String) -> Bool {
        let key = sport.lowercased().filter { $0.isLetter || $0.isNumber }
        return ["run", "running", "outdoorrun", "outdoorrunning", "trailrun", "trailrunning",
                "treadmill", "treadmillrun", "treadmillrunning", "indoorrun", "indoorrunning"].contains(key)
    }

    /// Pace uses only runs with both measured distance and duration. Missing values contribute to
    /// their own totals but cannot distort the pace of runs that do have usable measurements.
    public static func summary(_ sessions: [Session]) -> Summary {
        var distance = 0.0, duration = 0.0, paceDistance = 0.0, paceDuration = 0.0, longest = 0.0
        for session in sessions {
            let meters = session.distanceM.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
            let seconds = session.durationS.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
            let paceSeconds = session.paceDurationS.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
            if let meters { distance += meters; longest = max(longest, meters) }
            if let seconds { duration += seconds }
            if let meters, let paceSeconds { paceDistance += meters; paceDuration += paceSeconds }
        }
        return Summary(count: sessions.count, totalDistanceM: distance, totalDurationS: duration,
                       paceSecPerKm: paceDistance > 0 ? paceDuration / (paceDistance / 1000) : nil,
                       longestDistanceM: longest)
    }

    /// Cumulative distance and active seconds from the first accepted GPS fix. Active time excludes
    /// explicit pauses; equal distances with increasing time preserve stationary recording periods.
    public struct Sample: Equatable, Codable, Sendable {
        public let distanceM: Double
        public let elapsedS: Double

        public init(distanceM: Double, elapsedS: Double) {
            self.distanceM = distanceM
            self.elapsedS = elapsedS
        }
    }

    public struct Split: Equatable, Identifiable, Sendable {
        public let index: Int
        public let distanceM: Double
        public let durationS: Double
        public let paceSecPerKm: Double
        public let isPartial: Bool
        public var id: Int { index }
    }

    /// Linear interpolation locates kilometre/mile crossings within consecutive accepted GPS fixes.
    /// Reject malformed or regressing samples; cap the output to bound work on untrusted local data.
    public static func splits(_ samples: [Sample], unitMeters: Double = 1000) -> [Split] {
        guard unitMeters.isFinite, unitMeters > 0 else { return [] }
        var points: [Sample] = []
        for sample in samples {
            guard sample.distanceM.isFinite, sample.elapsedS.isFinite,
                  sample.distanceM >= 0, sample.elapsedS >= 0 else { continue }
            if let last = points.last {
                guard sample.distanceM >= last.distanceM, sample.elapsedS > last.elapsedS else { continue }
            }
            points.append(sample)
        }
        guard points.count >= 2, let first = points.first, let last = points.last,
              first.distanceM == 0, last.distanceM > 0 else { return [] }

        var result: [Split] = []
        var nextDistance = unitMeters
        var previousDistance = 0.0
        var previousTime = first.elapsedS
        for index in 1..<points.count {
            let a = points[index - 1], b = points[index]
            guard b.distanceM > a.distanceM else { continue }
            while nextDistance <= b.distanceM, result.count < 2000 {
                let fraction = (nextDistance - a.distanceM) / (b.distanceM - a.distanceM)
                let crossing = a.elapsedS + fraction * (b.elapsedS - a.elapsedS)
                let seconds = crossing - previousTime
                let meters = nextDistance - previousDistance
                guard seconds > 0, seconds.isFinite else { return result }
                result.append(Split(index: result.count + 1, distanceM: meters, durationS: seconds,
                                    paceSecPerKm: seconds / (meters / 1000), isPartial: false))
                previousDistance = nextDistance
                previousTime = crossing
                nextDistance += unitMeters
            }
            if result.count >= 2000 { return result }
        }
        let remaining = last.distanceM - previousDistance
        let seconds = last.elapsedS - previousTime
        if remaining > 0.01, seconds > 0, seconds.isFinite {
            result.append(Split(index: result.count + 1, distanceM: remaining, durationS: seconds,
                                paceSecPerKm: seconds / (remaining / 1000), isPartial: true))
        }
        return result
    }
}
