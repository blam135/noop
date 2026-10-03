package com.noop.location

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import com.noop.analytics.RunAnalysis
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GpsSessionTest {
    @Before fun resetBefore() { GpsSession.stop() }
    @After fun resetAfter() { GpsSession.stop() }
    private fun fix(lat: Double, time: Long) = AcceptedFix(LatLng(lat, 0.0), time)

    @Test fun usesFixTimestampsAndStartsTimingAtFirstFix() {
        GpsSession.start(1000, "Running")
        GpsSession.append(fix(0.0, 5000))
        GpsSession.append(fix(0.001, 15_000))
        val state = GpsSession.state.value
        assertEquals(RunAnalysis.Sample(0.0, 0.0), state.samples.first())
        assertEquals(10.0, state.samples.last().elapsedS, 0.0)
        assertEquals(15_000L, state.lastFixMs)
        assertEquals(10 / (state.distanceM / 1000), state.paceSecPerKm!!, 0.000001)
    }

    @Test fun pauseExcludesTimeAndTravelAndPreservesSeparateRouteSegments() {
        GpsSession.start(1000, "Running")
        GpsSession.append(fix(0.0, 1000))
        GpsSession.append(fix(0.001, 11_000))
        val before = GpsSession.state.value.distanceM
        GpsSession.pause(12_000)
        GpsSession.append(fix(0.5, 30_000))
        assertEquals(2, GpsSession.state.value.track.size)
        GpsSession.resume(52_000)
        GpsSession.append(fix(0.5, 30_000))
        assertEquals(2, GpsSession.state.value.track.size)
        GpsSession.append(fix(1.0, 53_000))
        val after = GpsSession.state.value
        assertEquals(before, after.distanceM, 0.0)
        assertEquals(12.0, after.samples.last().elapsedS, 0.0)
        assertEquals(listOf(2), after.segmentStartIndices)
        GpsSession.append(fix(1.001, 63_000))
        assertEquals(before + RouteMath.haversineMeters(LatLng(1.0, 0.0), LatLng(1.001, 0.0)),
            GpsSession.state.value.distanceM, 0.000001)
    }

    @Test fun checkpointRestoreDoesNotBridgeUnrecordedMovement() {
        GpsSession.start(1000, "Running")
        GpsSession.append(fix(0.0, 1000))
        GpsSession.append(fix(0.001, 11_000))
        val snapshot = GpsSession.state.value
        val decoded = GpsCheckpointCodec.decode(GpsCheckpointCodec.encode(snapshot))!!
        GpsSession.restore(decoded, nowMs = 60_000)
        GpsSession.append(fix(0.5, 40_000))
        assertEquals(2, GpsSession.state.value.track.size)
        GpsSession.append(fix(1.0, 60_000))
        assertEquals(snapshot.distanceM, GpsSession.state.value.distanceM, 0.0)
        assertEquals(59.0, GpsSession.state.value.samples.last().elapsedS, 0.0)
        assertEquals(listOf(2), GpsSession.state.value.segmentStartIndices)
    }

    @Test fun existingSessionCannotBeReplacedAndBadTimesCannotAddDistance() {
        GpsSession.start(1000, "Running")
        GpsSession.append(fix(0.0, 2000))
        GpsSession.start(9000, "Cycling")
        GpsSession.append(fix(1.0, 2000))
        GpsSession.append(fix(Double.NaN, 4000))
        assertEquals("Running", GpsSession.state.value.sportName)
        assertEquals(1, GpsSession.state.value.track.size)
        assertEquals(0.0, GpsSession.state.value.distanceM, 0.0)
    }

    @Test fun providerOutageStartsANewSegmentWithoutCountingMissingMovement() {
        GpsSession.start(1000, "Running")
        GpsSession.append(fix(0.0, 1000))
        GpsSession.append(fix(0.001, 11_000))
        val before = GpsSession.state.value.distanceM
        GpsSession.markUnavailable(nowMs = 15_000)
        GpsSession.append(fix(0.5, 12_000))
        assertEquals(2, GpsSession.state.value.track.size)
        assertTrue(GpsSession.state.value.unavailable)
        GpsSession.append(fix(1.0, 20_000))
        assertFalse(GpsSession.state.value.unavailable)
        assertEquals(before, GpsSession.state.value.distanceM, 0.0)
        assertEquals(listOf(2), GpsSession.state.value.segmentStartIndices)
    }
}
