package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test
import com.noop.analytics.RunAnalysis.Sample
import com.noop.analytics.RunAnalysis.Session

/** Fixtures match Packages/StrandAnalytics/Tests/StrandAnalyticsTests/RunAnalysisTests.swift. */
class RunAnalysisTest {
    @Test fun recognizesStoredRunningNamesWithoutMatchingOtherSports() {
        listOf("Running", "run", "TrailRunning", "Treadmill run", "Outdoor Run").forEach {
            assertTrue(it, RunAnalysis.isRunningSport(it))
        }
        listOf("Walking", "Running circuit", "Cycling", "Rowing", "").forEach {
            assertFalse(it, RunAnalysis.isRunningSport(it))
        }
    }

    @Test fun summaryWeightsPaceByDistanceAndExcludesMissingPairs() {
        val result = RunAnalysis.summary(listOf(
            Session(1, 1000.0, 300.0), Session(2, 9000.0, 3600.0),
            Session(3, null, 1800.0), Session(4, 5000.0, null),
            Session(5, Double.NaN, Double.NEGATIVE_INFINITY),
        ))
        assertEquals(5, result.count)
        assertEquals(15000.0, result.totalDistanceM, 0.0)
        assertEquals(5700.0, result.totalDurationS, 0.0)
        assertEquals(9000.0, result.longestDistanceM, 0.0)
        assertEquals(390.0, result.paceSecPerKm!!, 0.0)
        assertNull(RunAnalysis.summary(emptyList()).paceSecPerKm)
    }

    @Test fun crossingInterpolationAndPartialSplit() {
        val result = RunAnalysis.splits(listOf(Sample(0.0, 0.0), Sample(800.0, 240.0),
            Sample(1200.0, 400.0), Sample(2500.0, 790.0)))
        assertEquals(3, result.size)
        assertEquals(320.0, result[0].durationS, 0.001)
        assertEquals(320.0, result[1].durationS, 0.001)
        assertEquals(500.0, result[2].distanceM, 0.0)
        assertEquals(150.0, result[2].durationS, 0.001)
        assertEquals(300.0, result[2].paceSecPerKm, 0.001)
        assertTrue(result[2].isPartial)
        assertFalse(result[0].isPartial)
    }

    @Test fun measuredPaceDurationDoesNotReplaceTotalActiveTime() {
        val result = RunAnalysis.summary(listOf(Session(1, 1000.0, 420.0, 300.0)))
        assertEquals(420.0, result.totalDurationS, 0.0)
        assertEquals(300.0, result.paceSecPerKm!!, 0.0)
    }

    @Test fun stationaryTimeCountsButDoesNotCreateDistance() {
        val result = RunAnalysis.splits(listOf(Sample(0.0, 0.0), Sample(500.0, 150.0),
            Sample(500.0, 210.0), Sample(1000.0, 360.0)))
        assertEquals(1, result.size)
        assertEquals(360.0, result[0].durationS, 0.0)
        assertFalse(result[0].isPartial)
    }

    @Test fun mileSplitsAndShortRuns() {
        val mile = 1609.344
        val result = RunAnalysis.splits(listOf(Sample(0.0, 0.0), Sample(mile * 2, 960.0)), mile)
        assertEquals(2, result.size)
        assertEquals(480.0, result[0].durationS, 0.0)
        assertEquals(mile, result[0].distanceM, 0.0)
        val short = RunAnalysis.splits(listOf(Sample(0.0, 0.0), Sample(250.0, 75.0)))
        assertEquals(1, short.size)
        assertTrue(short[0].isPartial)
        assertEquals(300.0, short[0].paceSecPerKm, 0.0)
    }

    @Test fun malformedSamplesCannotCreateInvalidPaces() {
        val result = RunAnalysis.splits(listOf(Sample(0.0, 0.0), Sample(Double.NaN, 40.0),
            Sample(500.0, 150.0), Sample(100.0, 160.0), Sample(800.0, 140.0), Sample(1000.0, 300.0)))
        assertEquals(1, result.size)
        assertEquals(300.0, result[0].durationS, 0.0)
        assertTrue(RunAnalysis.splits(emptyList(), 0.0).isEmpty())
        assertTrue(RunAnalysis.splits(listOf(Sample(50.0, 5.0), Sample(1000.0, 300.0))).isEmpty())
        assertTrue(RunAnalysis.splits(listOf(Sample(0.0, 0.0))).isEmpty())
    }
}
