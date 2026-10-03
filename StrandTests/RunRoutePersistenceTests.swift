import XCTest
import CoreLocation
import StrandAnalytics
@testable import Strand

@MainActor
final class RunRoutePersistenceTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "test.runRoute.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    private func fix(lat: Double, timeMs: Int64) -> CLLocation {
        CLLocation(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: 151),
                   altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5,
                   timestamp: Date(timeIntervalSince1970: Double(timeMs) / 1000))
    }

    func testLegacyRoutesDecodeWithoutTimingSamples() throws {
        let data = Data(#"{"1|Running":{"polyline":"abc","distanceM":123}}"#.utf8)
        let routes = RouteStore.decodeMap(data)
        XCTAssertEqual(routes["1|Running"]?.distanceM, 123)
        XCTAssertNil(routes["1|Running"]?.samples)
    }

    func testNonfiniteTimingCannotEraseOtherSavedRoutes() {
        let broken = WorkoutRoute(polyline: "abc", distanceM: 100,
                                  samples: [.init(distanceM: 100, elapsedS: .nan)])
        let good = WorkoutRoute(polyline: "def", distanceM: 200,
                                samples: [.init(distanceM: 0, elapsedS: 0), .init(distanceM: 200, elapsedS: 60)])
        let routes = RouteStore.decodeMap(RouteStore.encodeMap(["1|Running": broken, "2|Running": good]))
        XCTAssertNil(routes["1|Running"]?.samples)
        XCTAssertEqual(routes["2|Running"], good)
    }

    func testSamplesUseFixTimesAndExcludeWaitingForFirstFix() throws {
        let recorder = GpsWorkoutRecorder(defaults: defaults(), usesLocationServices: false)
        let start: Int64 = 1_700_000_000_000
        recorder.start(startMs: start)
        recorder.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -33, timeMs: start + 5000),
            fix(lat: -32.9998, timeMs: start + 15_000),
        ])
        recorder.stop()
        let samples = try XCTUnwrap(recorder.capturedRoute()?.samples)
        XCTAssertEqual(samples.first, .init(distanceM: 0, elapsedS: 0))
        XCTAssertEqual(samples.last?.elapsedS, 10)
        XCTAssertGreaterThan(samples.last?.distanceM ?? 0, 20)
        XCTAssertEqual(recorder.paceSecPerKm!, 10 / ((samples.last?.distanceM ?? 0) / 1000), accuracy: 0.001)
    }

    func testCheckpointRestoresOnlyMatchingWorkoutAndDoesNotInventGapDistance() throws {
        let store = defaults()
        let start: Int64 = 1_700_000_000_123
        let original = GpsWorkoutRecorder(defaults: store, usesLocationServices: false)
        original.start(startMs: start)
        original.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -33, timeMs: start + 5000),
            fix(lat: -32.9998, timeMs: start + 15_000),
        ])
        let restored = GpsWorkoutRecorder(defaults: store, usesLocationServices: false,
                                          nowMs: { start + 60_000 })
        restored.restore(startMs: start / 1000 * 1000, pausedAtMs: nil, pausedDurationMs: 0)
        XCTAssertEqual(restored.pointCount, 2)
        let recordedDistance = restored.distanceM
        restored.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -32.9997, timeMs: start + 30_000),
        ])
        XCTAssertEqual(restored.pointCount, 2)
        restored.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -32.95, timeMs: start + 75_000),
        ])
        XCTAssertEqual(restored.distanceM, recordedDistance, accuracy: 0.001)
        XCTAssertEqual(restored.capturedRoute()?.segmentStartIndices, [2])
        XCTAssertEqual(restored.capturedRoute()?.samples?.last?.elapsedS, 70)
        let nextRestore = GpsWorkoutRecorder(defaults: store, usesLocationServices: false,
                                            nowMs: { start + 80_000 })
        nextRestore.restore(startMs: start / 1000 * 1000, pausedAtMs: nil, pausedDurationMs: 0)
        XCTAssertEqual(nextRestore.routeSegmentStartIndices, [2])
        let different = GpsWorkoutRecorder(defaults: store, usesLocationServices: false)
        different.restore(startMs: start + 100_000, pausedAtMs: nil, pausedDurationMs: 0)
        XCTAssertEqual(different.pointCount, 0)
        original.stop(); restored.stop(); nextRestore.stop(); different.stop()
    }

    func testPauseKeepsRouteAndResumeDoesNotMeasurePausedMovement() {
        let start: Int64 = 1_700_000_000_000
        var now = start + 20_000
        let recorder = GpsWorkoutRecorder(defaults: defaults(), usesLocationServices: false, nowMs: { now })
        recorder.start(startMs: start)
        recorder.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -33, timeMs: start + 5000),
            fix(lat: -32.9998, timeMs: start + 15_000),
        ])
        let previousDistance = recorder.distanceM
        recorder.pause()
        XCTAssertEqual(recorder.status, .paused)
        XCTAssertEqual(recorder.routePoints.count, 2)
        now = start + 80_000
        recorder.resume()
        recorder.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -32.97, timeMs: start + 75_000),
        ])
        XCTAssertEqual(recorder.pointCount, 2)
        recorder.locationManager(CLLocationManager(), didUpdateLocations: [
            fix(lat: -32.95, timeMs: start + 85_000),
        ])
        XCTAssertEqual(recorder.distanceM, previousDistance, accuracy: 0.001)
        XCTAssertEqual(recorder.capturedRoute()?.samples?.last?.elapsedS, 20)
        XCTAssertEqual(recorder.routeSegmentStartIndices, [2])
        recorder.stop()
    }

    func testFilterRejectsNonfiniteAccuracyAndNonincreasingTime() {
        let filter = TrackFilter()
        XCTAssertNil(filter.accept(RawFix(lat: -33, lon: 151, accuracyM: .nan, tMs: 1000)))
        XCTAssertNotNil(filter.accept(RawFix(lat: -33, lon: 151, accuracyM: 5, tMs: 1000)))
        XCTAssertNil(filter.accept(RawFix(lat: -32.99, lon: 151, accuracyM: 5, tMs: 1000)))
        XCTAssertNil(filter.accept(RawFix(lat: -32.99, lon: 151, accuracyM: 5, tMs: 999)))
    }
}
