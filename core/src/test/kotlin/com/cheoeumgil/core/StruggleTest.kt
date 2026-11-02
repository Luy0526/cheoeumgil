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
        d.onLocation(north(0.0), 0)
        d.onLocation(north(16.7), 1_000)            // ~60 km/h
        d.onBrake(true, 1_100)
        val e = d.onLocation(north(18.7), 2_000)    // 2 m in 1 s, ~7 km/h
        assertEquals(StruggleKind.SUDDEN_BRAKE, e?.kind)

        val gentle = StruggleDetector()
        gentle.onLocation(north(0.0), 0)
        gentle.onLocation(north(16.7), 1_000)
        gentle.onBrake(true, 1_100)
        assertNull(gentle.onLocation(north(30.0), 2_000)) // still ~48 km/h
    }

    @Test
    fun signalFlippingIsHesitationButOneLaneChangeIsNot() {
        val d = StruggleDetector()
        d.onLocation(home, 0)
        assertNull(d.onTurnSignal(true, 1_000))
        assertNull(d.onTurnSignal(false, 5_000))   // normal lane change
        assertNull(d.onTurnSignal(true, 9_000))
        assertEquals(StruggleKind.SIGNAL_HESITATION, d.onTurnSignal(false, 12_000)?.kind)

        val slow = StruggleDetector()
        slow.onLocation(home, 0)
        listOf(0L, 20_000, 40_000, 60_000).forEachIndexed { i, t -> assertNull(slow.onTurnSignal(i % 2 == 0, t)) }
    }

    @Test
    fun repeatedSignalStateIsNotAFlip() {
        val d = StruggleDetector()
        d.onLocation(home, 0)
        listOf(true, true, true, false, false).forEachIndexed { i, on ->
            assertNull(d.onTurnSignal(on, 1_000L * i))  // one lane change reported 5 times = 2 real changes
        }
    }

    @Test
    fun cruiseDropAndRerouteNeedAPosition() {
        val d = StruggleDetector()
        assertNull(d.onReRoute(0))                 // no location yet
        d.onLocation(home, 0)
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
        assertEquals(listOf("500미터 앞은 다른 운전자들이 경로를 자주 벗어난 구간이에요. 놓치기 쉬운 곳이니, 갈 길을 미리 준비하세요."), e.onHotspots(found))
        assertTrue(e.onHotspots(found).isEmpty())
    }
}
