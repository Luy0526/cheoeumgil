package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private val home = Spot(37.4127, 127.0947)
    private fun north(m: Double) = home.copy(lat = home.lat + m / 111_320.0)
    private val e = StruggleEvent(StruggleKind.REROUTE, north(1000.0), 1_761_000_000_000, "trip-1")

    @Test
    fun eventsAndHotspotsRoundTrip() {
        assertEquals(listOf(e), Wire.decodeEvents(Wire.encodeEvents(listOf(e))))
        val h = Hotspot(north(500.0), 4, StruggleKind.SUDDEN_BRAKE)
        assertEquals(listOf(h), Wire.decodeHotspots(Wire.encodeHotspots(listOf(h))))
    }

    @Test
    fun malformedOrHostileLinesAreDropped() {
        val body = listOf(
            Wire.encodeEvents(listOf(e)),
            "REROUTE,91.0,127.0,1,trip-2",          // lat out of range
            "HACK,37.0,127.0,1,trip-2",             // unknown kind
            "REROUTE,37.0,127.0,1,trip 2;rm",       // bad trip id
            "REROUTE,37.0,127.0,-5,trip-2",         // bad time
            "REROUTE,37.0,127.0",                   // too few fields
            "",
        ).joinToString("\n")
        assertEquals(listOf(e), Wire.decodeEvents(body))
    }

    @Test
    fun eventsNearTripStartOrDestinationStayInTheCar() {
        val nearHome = e.copy(spot = north(100.0))
        val noTrip = e.copy(tripId = "")
        assertEquals(listOf(e), Wire.uploadable(listOf(nearHome, e, noTrip), listOf(home)))
        assertTrue(Wire.uploadable(listOf(nearHome), listOf(home, north(5000.0))).isEmpty())
        assertTrue(Wire.uploadable(listOf(e), emptyList()).isEmpty()) // start unknown -> keep everything
    }

    @Test
    fun hotspotQueryUsesACoarsePosition() {
        assertEquals(Spot(37.41, 127.09), Wire.coarse(home))
        assertTrue(StruggleDetector.metres(home, Wire.coarse(home)) < 1_000)
    }
}
