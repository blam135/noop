import XCTest
@testable import StrandAnalytics

final class RunAnalysisTests: XCTestCase {
    func testRecognizesStoredRunningNamesWithoutMatchingOtherSports() {
        for sport in ["Running", "run", "TrailRunning", "Treadmill run", "Outdoor Run"] {
            XCTAssertTrue(RunAnalysis.isRunningSport(sport), sport)
        }
        for sport in ["Walking", "Running circuit", "Cycling", "Rowing", ""] {
            XCTAssertFalse(RunAnalysis.isRunningSport(sport), sport)
        }
    }

    func testSummaryWeightsPaceByDistanceAndExcludesMissingPairs() {
        let sessions = [
            RunAnalysis.Session(startTs: 1, distanceM: 1000, durationS: 300),
            RunAnalysis.Session(startTs: 2, distanceM: 9000, durationS: 3600),
            RunAnalysis.Session(startTs: 3, distanceM: nil, durationS: 1800),
            RunAnalysis.Session(startTs: 4, distanceM: 5000, durationS: nil),
            RunAnalysis.Session(startTs: 5, distanceM: .nan, durationS: -.infinity)
        ]
        let summary = RunAnalysis.summary(sessions)
        XCTAssertEqual(summary.count, 5)
        XCTAssertEqual(summary.totalDistanceM, 15000)
        XCTAssertEqual(summary.totalDurationS, 5700)
        XCTAssertEqual(summary.longestDistanceM, 9000)
        XCTAssertEqual(summary.paceSecPerKm, 390)
        XCTAssertNil(RunAnalysis.summary([]).paceSecPerKm)
    }

    func testCrossingInterpolationAndPartialSplit() {
        let result = RunAnalysis.splits([
            .init(distanceM: 0, elapsedS: 0),
            .init(distanceM: 800, elapsedS: 240),
            .init(distanceM: 1200, elapsedS: 400),
            .init(distanceM: 2500, elapsedS: 790)
        ])
        XCTAssertEqual(result.count, 3)
        XCTAssertEqual(result[0].durationS, 320, accuracy: 0.001)
        XCTAssertEqual(result[1].durationS, 320, accuracy: 0.001)
        XCTAssertEqual(result[2].distanceM, 500)
        XCTAssertEqual(result[2].durationS, 150, accuracy: 0.001)
        XCTAssertEqual(result[2].paceSecPerKm, 300, accuracy: 0.001)
        XCTAssertTrue(result[2].isPartial)
        XCTAssertFalse(result[0].isPartial)
    }

    func testGpsPaceTimingDoesNotChangeWholeSessionDuration() {
        let summary = RunAnalysis.summary([
            .init(startTs: 1, distanceM: 1000, durationS: 420, paceDurationS: 300)
        ])
        XCTAssertEqual(summary.totalDurationS, 420)
        XCTAssertEqual(summary.paceSecPerKm, 300)
    }

    func testStationaryTimeCountsButDoesNotCreateDistance() {
        let result = RunAnalysis.splits([
            .init(distanceM: 0, elapsedS: 0),
            .init(distanceM: 500, elapsedS: 150),
            .init(distanceM: 500, elapsedS: 210),
            .init(distanceM: 1000, elapsedS: 360)
        ])
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].durationS, 360)
        XCTAssertFalse(result[0].isPartial)
    }

    func testMileSplitsAndShortRuns() {
        let mile = 1609.344
        let result = RunAnalysis.splits([
            .init(distanceM: 0, elapsedS: 0),
            .init(distanceM: mile * 2, elapsedS: 960)
        ], unitMeters: mile)
        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(result[0].durationS, 480)
        XCTAssertEqual(result[0].distanceM, mile)
        let short = RunAnalysis.splits([.init(distanceM: 0, elapsedS: 0),
                                       .init(distanceM: 250, elapsedS: 75)])
        XCTAssertEqual(short.count, 1)
        XCTAssertTrue(short[0].isPartial)
        XCTAssertEqual(short[0].paceSecPerKm, 300)
    }

    func testMalformedSamplesCannotCreateInvalidPaces() {
        let result = RunAnalysis.splits([
            .init(distanceM: 0, elapsedS: 0),
            .init(distanceM: .nan, elapsedS: 40),
            .init(distanceM: 500, elapsedS: 150),
            .init(distanceM: 100, elapsedS: 160),
            .init(distanceM: 800, elapsedS: 140),
            .init(distanceM: 1000, elapsedS: 300)
        ])
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].durationS, 300)
        XCTAssertTrue(RunAnalysis.splits([], unitMeters: 0).isEmpty)
        XCTAssertTrue(RunAnalysis.splits([.init(distanceM: 50, elapsedS: 5),
                                        .init(distanceM: 1000, elapsedS: 300)]).isEmpty)
        XCTAssertTrue(RunAnalysis.splits([.init(distanceM: 0, elapsedS: 0)]).isEmpty)
    }
}
