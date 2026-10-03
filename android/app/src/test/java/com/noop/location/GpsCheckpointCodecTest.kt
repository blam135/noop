package com.noop.location

import com.noop.analytics.RouteMath.LatLng
import com.noop.analytics.RunAnalysis.Sample
import com.noop.analytics.RouteMath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GpsCheckpointCodecTest {
    private val state = GpsSession.State(active = true, startMs = 1000, sportName = "Running",
        track = listOf(LatLng(0.0, 0.0), LatLng(0.001, 0.0), LatLng(1.0, 0.0)), distanceM = 111.0,
        paused = true, pausedAtMs = 31_000, pausedDurationMs = 5000,
        samples = listOf(Sample(0.0, 0.0), Sample(111.0, 10.0), Sample(111.0, 25.0)),
        originActiveMs = 0, lastFixMs = 31_000, segmentStartIndices = listOf(2))

    @Test fun roundTripsRouteTimingPauseAndGaps() {
        val decoded = GpsCheckpointCodec.decode(GpsCheckpointCodec.encode(state))!!
        assertEquals(state, decoded)
    }

    @Test fun malformedCheckpointDoesNotReviveAWorkout() {
        assertNull(GpsCheckpointCodec.decode("{torn"))
        assertNull(GpsCheckpointCodec.decode(JSONObject(GpsCheckpointCodec.encode(state)).put("startMs", 0).toString()))
        assertNull(GpsCheckpointCodec.decode(JSONObject(GpsCheckpointCodec.encode(state))
            .put("track", JSONArray().put(JSONArray(listOf(91.0, 0.0)))).toString()))
        assertNull(GpsCheckpointCodec.decode(JSONObject(GpsCheckpointCodec.encode(state)).put("pausedAtMs", JSONObject.NULL).toString()))
        assertTrue(RunTrackCodec.samples(JSONArray().put(JSONObject().put("distanceM", -1).put("elapsedS", 2))).isEmpty())
        assertTrue(RunTrackCodec.samples(JSONArray().put(JSONObject().put("distanceM", 0).put("elapsedS", 3))
            .put(JSONObject().put("distanceM", 1).put("elapsedS", 2))).isEmpty())
    }

    @Test fun gappedGeometryNeverLosesItsBreakMetadata() {
        val json = JSONObject().put("samples", RunTrackCodec.encodeSamples(state.samples))
            .put("polyline", RouteMath.encode(state.track)).put("segmentStartIndices", JSONArray(listOf(2)))
        assertNotNull(RunTrackCodec.track(json).polyline)
        assertNull(RunTrackCodec.track(JSONObject(json.toString()).removeGapMetadata()).polyline)
        assertNull(RunTrackCodec.track(JSONObject(json.toString()).put("segmentStartIndices", JSONArray(listOf(100)))).polyline)
        assertNull(RunTrackCodec.track(JSONObject().put("polyline", JSONObject.NULL)).polyline)
    }
    private fun JSONObject.removeGapMetadata(): JSONObject = apply { remove("segmentStartIndices") }
}
