package com.cheoeumgil.server

import com.cheoeumgil.core.Spot
import com.cheoeumgil.core.StruggleEvent
import com.cheoeumgil.core.StruggleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HotspotStoreTest {
    private val spot = Spot(37.4235, 127.0947)

    @Test
    fun hotspotAppearsAfterThreeTripsAndSurvivesRestart() {
        val file = File.createTempFile("events", ".csv").apply { deleteOnExit(); writeText("") }
        val store = HotspotStore(file)
        store.add((1..2).map { StruggleEvent(StruggleKind.REROUTE, spot, 1_000L + it, "trip-$it") })
        assertTrue(store.near(spot, 1_000.0).isEmpty())
        store.add(listOf(StruggleEvent(StruggleKind.REROUTE, spot, 1_003L, "trip-3")))
        assertEquals(3, store.near(spot, 1_000.0).single().trips)
        assertTrue(store.near(Spot(35.1, 129.0), 1_000.0).isEmpty()) // Busan: out of range

        assertEquals(3, HotspotStore(file).near(spot, 1_000.0).single().trips) // reloaded from disk
    }

    @Test
    fun resendingABatchDoesNotDoubleCount() {
        val file = File.createTempFile("events", ".csv").apply { deleteOnExit(); writeText("") }
        val store = HotspotStore(file)
        val batch = listOf(StruggleEvent(StruggleKind.REROUTE, spot, 1_000L, "trip-1"))
        assertEquals(1, store.add(batch))
        assertEquals(0, store.add(batch + batch))
        assertEquals(1, store.size())
        assertEquals(1, HotspotStore(file).size())
    }
}
