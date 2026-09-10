package com.lyrebird.rc.logger

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flight video manifest matching.
 *
 * The manifest is metadata-only: the DJI filename timestamp is parsed and matched against the
 * flight-session window, so a video is attributed to the flight that actually recorded it and no
 * bytes ever move.
 */
class FlightVideoManifestTest {

    private fun epochOf(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis / 1000

    @Test
    fun djiVideoTimestampIsParsedFromTheFilename() {
        val expected = epochOf(2026, 9, 10, 17, 26, 48)
        assertEquals(expected, FlightVideoManifest.parseDjiVideoTimestamp("DJI_20260910172648_0008_D.MP4"))
        assertEquals(expected, FlightVideoManifest.parseDjiVideoTimestamp("dji_20260910172648_0008_D.mp4"))
        assertEquals(expected, FlightVideoManifest.parseDjiVideoTimestamp("DJI_20260910172648_0008_D.MOV"))
    }

    @Test
    fun nonVideoFilesAreNotMatched() {
        assertNull(FlightVideoManifest.parseDjiVideoTimestamp("DJI_20260910172648_0008_D.SRT"))
        assertNull(FlightVideoManifest.parseDjiVideoTimestamp("DJI_20260910172648_0008_D.LRF"))
        assertNull(FlightVideoManifest.parseDjiVideoTimestamp("DJI_20260910172648_0008_D.JPG"))
        assertNull(FlightVideoManifest.parseDjiVideoTimestamp("not_a_dji_name.MP4"))
    }

    @Test
    fun onlyVideosInsideTheWindowAreMatched() {
        val window = epochOf(2026, 9, 10, 17, 30, 0) to epochOf(2026, 9, 10, 17, 45, 0)
        val entries = listOf(
            entry("DJI_20260910172830_0008_D.MP4"), // just inside the 120 s margin before the session
            entry("DJI_20260910172600_0008_D.MP4"), // before the margin
            entry("DJI_20260910173510_0009_D.MP4"), // inside
            entry("DJI_20260910175000_0010_D.MP4"), // after the session
            entry("DJI_20260910174000_0011_D.MP4")  // inside
        )

        val matched = FlightVideoManifest.matchFlightVideos(
            entries, window, FlightVideoManifest.WINDOW_MARGIN_SECONDS
        )

        assertEquals(listOf("DJI_20260910172830_0008_D.MP4", "DJI_20260910173510_0009_D.MP4", "DJI_20260910174000_0011_D.MP4"),
            matched.map { it.fileName })
    }

    @Test
    fun manifestCarriesTheProposedPrefixedNameAndCompanions() {
        val window = epochOf(2026, 9, 10, 17, 30, 0) to epochOf(2026, 9, 10, 17, 45, 0)
        val videos = listOf(
            FlightVideoManifest.VideoEntry(
                fileName = "DJI_20260910173510_0009_D.MP4",
                sizeBytes = 4096,
                subFiles = listOf("DJI_20260910173510_0009_D.SRT", "DJI_20260910173510_0009_D.LRF")
            )
        )

        val json = FlightVideoManifest.manifestJson("17-30-00_mini3.jsonl", "mini3", "ABC123", window, videos)

        assertEquals("17-30-00_mini3.jsonl", json.getString("flightLog"))
        assertEquals("mini3", json.getString("droneName"))
        assertEquals("ABC123", json.getString("vehicleSerial"))
        val video = json.getJSONArray("videos").getJSONObject(0)
        assertEquals("DJI_20260910173510_0009_D.MP4", video.getString("original"))
        assertEquals("mini3_ABC123_DJI_20260910173510_0009_D.MP4", video.getString("proposedName"))
        assertEquals(4096L, video.getLong("sizeBytes"))
        assertEquals(2, video.getJSONArray("subFiles").length())
        assertTrue(video.getLong("recordStartEpochSec") > 0)
    }

    private fun entry(fileName: String) =
        FlightVideoManifest.VideoEntry(fileName, sizeBytes = 0, subFiles = emptyList())
}
