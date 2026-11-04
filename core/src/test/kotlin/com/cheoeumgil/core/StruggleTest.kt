package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StruggleTest {
    private val home = Spot(37.4127, 127.0947) // emulator start position, 수정구 시흥동
    private fun north(m: Double) = home.copy(lat = home.lat + m / 111_320.0)

    @Test
    fun suddenBrakeNeedsARealSpeedDrop() {
        val d = StruggleDetector().apply { tripId = "t1" }
        d.onLocation(north(0.0), 0, 0.0)
        d.onLocation(north(16.7), 1_000, 0.0)            // ~60 km/h
        d.onBrake(true, 1_100)
        val e = d.onLocation(north(18.7), 2_000, 0.0)    // 2 m in 1 s, ~7 km/h
        assertEquals(StruggleKind.SUDDEN_BRAKE, e?.kind)

        val gentle = StruggleDetector()
        gentle.onLocation(north(0.0), 0, 0.0)
        gentle.onLocation(north(16.7), 1_000, 0.0)
        gentle.onBrake(true, 1_100)
        assertNull(gentle.onLocation(north(30.0), 2_000, 0.0)) // still ~48 km/h
    }

    @Test
    fun aLongSignalWithoutTurningIsAFailedMerge() {
        val d = StruggleDetector()
        d.onLocation(home, 0, 0.0)
        assertNull(d.onTurnSignal(true, 1_000))
        assertNull(d.onTurnSignal(false, 5_000))    // 4 s: a normal lane change
        assertNull(d.onTurnSignal(true, 9_000))
        assertNull(d.onTurnSignal(true, 12_000))    // repeated "on" report keeps the 9 s start
        d.onLocation(north(40.0), 15_000, 5.0)      // crawling, same direction
        assertEquals(StruggleKind.SIGNAL_HESITATION, d.onTurnSignal(false, 19_000)?.kind) // 10 s waiting for a gap
        assertNull(d.onTurnSignal(false, 20_000))   // a second "off" is not another event
    }

    @Test
    fun aLongSignalThatEndsInATurnIsAJunctionTurn() {
        val d = StruggleDetector()
        d.onLocation(home, 0, 0.0)
        assertNull(d.onTurnSignal(true, 1_000))     // waiting at a light to turn right
        d.onLocation(north(10.0), 30_000, 90.0)     // turned
        assertNull(d.onTurnSignal(false, 31_000))
    }

    @Test
    fun cruiseDropAndRerouteNeedAPosition() {
        val d = StruggleDetector()
        assertNull(d.onReRoute(0))                 // no location yet
        d.onLocation(home, 0, 0.0)
        assertEquals(StruggleKind.REROUTE, d.onReRoute(1)?.kind)
        assertNull(d.onCruise(false, 2))
        assertNull(d.onCruise(true, 3))
        assertEquals(StruggleKind.CRUISE_DROP, d.onCruise(false, 4)?.kind)
    }

    @Test
    fun hotspotNeedsThreeDifferentTrips() {
        fun ev(kind: StruggleKind, trip: String, dm: Double = 0.0) = StruggleEvent(kind, north(dm), 0, trip)
        val two = listOf(ev(StruggleKind.REROUTE, "a"), ev(StruggleKind.REROUTE, "a", 5.0), ev(StruggleKind.REROUTE, "b"))
        assertTrue(hotspots(two).isEmpty())
        val three = two + ev(StruggleKind.SUDDEN_BRAKE, "c", 10.0)
        val h = hotspots(three).single()
        assertEquals(3, h.trips)
        assertEquals(StruggleKind.REROUTE, h.main)
    }

    @Test
    fun onlyHotspotsAheadAndInRange() {
        val ahead = Hotspot(north(500.0), 3, StruggleKind.REROUTE)
        val behind = Hotspot(north(-500.0), 3, StruggleKind.REROUTE)
        val far = Hotspot(north(3000.0), 3, StruggleKind.REROUTE)
        val found = hotspotsAhead(home, 0.0, listOf(far, behind, ahead))
        assertEquals(listOf(ahead), found.map { it.first })
        assertEquals(500.0, found.single().second, 1.0)

        val e = BriefingEngine()
        assertEquals(listOf("500미터 앞은 서로 다른 주행 3번에서 경로를 벗어난 구간이에요. 놓치기 쉬운 곳이니, 갈 길을 미리 준비하세요."), e.onHotspots(found))
        assertTrue(e.onHotspots(found).isEmpty())
    }

    @Test
    fun theOtherCarriagewayIsKeptApart() {
        // three trips struggled heading south at the same spot: not a hotspot for northbound, and not merged with it
        val south = (1..3).map { StruggleEvent(StruggleKind.SUDDEN_BRAKE, north(500.0), 0, "s$it", 180.0) }
        val northbound = (1..2).map { StruggleEvent(StruggleKind.SUDDEN_BRAKE, north(500.0), 0, "n$it", 0.0) }
        val spots = hotspots(south + northbound)
        assertEquals(listOf(180.0), spots.map { Math.round(it.headingDeg).toDouble() })
        assertTrue(hotspotsAhead(home, 0.0, spots).isEmpty())
        assertEquals(1, hotspotsAhead(north(1000.0), 180.0, spots).size)
    }
}
