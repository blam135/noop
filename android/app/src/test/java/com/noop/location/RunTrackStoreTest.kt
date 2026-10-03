package com.noop.location

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import com.noop.analytics.RunAnalysis.Sample
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** File-backed tests verify editing/merging/deleting activity keys preserve or clear GPS metadata. */
class RunTrackStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private fun store() = RunTrackStore(directory.root,
        readFile = { it.readText() }, writeFile = { file, raw -> file.writeText(raw) },
        deleteFile = { it.delete(); Unit })
    private val from = RunTrackStore.Key(1700000000, "Running")
    private val to = RunTrackStore.Key(1700000500, "Trail run")
    private val polyline = RouteMath.encode(listOf(LatLng(0.0, 0.0), LatLng(0.001, 0.0), LatLng(1.0, 0.0)))
    private val samples = listOf(Sample(0.0, 0.0), Sample(111.0, 30.0), Sample(111.0, 60.0))

    @Test fun editedKeyRetainsTimedSamplesAndSegmentedGeometry() {
        val store = store()
        assertTrue(store.save(from.startTs, from.sport, samples, listOf(2), polyline))
        store.rekey(from, to)
        assertNull(store.load(from.startTs, from.sport))
        assertEquals(RunTrackStore.Track(samples, listOf(2), polyline), store.load(to.startTs, to.sport))
    }

    @Test fun failedEditedSaveLeavesTheOldMetadataRecoverable() {
        val store = store()
        assertTrue(store.save(from.startTs, from.sport, samples, listOf(2), polyline))
        val failedWriter = RunTrackStore(directory.root, readFile = { it.readText() },
            writeFile = { _, _ -> throw java.io.IOException("disk unavailable") }, deleteFile = { it.delete(); Unit })
        failedWriter.rekey(from, to)
        assertNotNull(store.load(from.startTs, from.sport))
        assertNull(store.load(to.startTs, to.sport))
    }

    @Test fun mergedRunRetainsRepresentativeRouteButNeverConstituentTiming() {
        val store = store()
        assertTrue(store.save(from.startTs, from.sport, samples, listOf(2), polyline))
        assertTrue(store.save(to.startTs, to.sport, samples, emptyList()))
        val representative = store.load(from.startTs, from.sport)!!
        store.consolidate(listOf(from, to), to, representative)
        assertNull(store.load(from.startTs, from.sport))
        val merged = store.load(to.startTs, to.sport)!!
        assertEquals(polyline, merged.polyline)
        assertEquals(listOf(2), merged.segmentStartIndices)
        assertTrue(merged.samples.isEmpty())
    }

    @Test fun deletionRemovesTimingAndGeometryTogether() {
        val store = store()
        store.save(from.startTs, from.sport, samples, listOf(2), polyline)
        store.remove(from)
        assertNull(store.load(from.startTs, from.sport))
        assertTrue(directory.root.listFiles()!!.isEmpty())
    }
}
