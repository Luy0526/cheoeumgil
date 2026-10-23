package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteHistoryTest {
    private val route = listOf(
        Turn("TURN_AVOID_UNDER_R", 1190.0, "판교IC 방면"),
        Turn("TURN_RIGHT", 820.0, "경기창조경제혁신센터 방면"),
        Turn("TURN_RIGHT", 200.0, "이름 없는 도로"),
        Turn("TURN_ARRIVE", 10.0, "도착"),
    )

    @Test
    fun familiarAfterThreeArrivalsAndSurvivesSaveLoad() {
        val h = RouteHistory()
        repeat(2) { h.arrived("경기도판교테크노밸리") }
        assertFalse(h.isFamiliar("경기도판교테크노밸리"))
        h.arrived("경기도판교테크노밸리")
        assertTrue(h.isFamiliar("경기도판교테크노밸리"))
        assertFalse(h.isFamiliar("제주공항"))

        val loaded = RouteHistory().apply { decode(h.encode() + "\nbroken line") }
        assertTrue(loaded.isFamiliar("경기도판교테크노밸리"))
    }

    @Test
    fun quietModeKeepsOnlyNearRemindersForHardSections() {
        val e = BriefingEngine().apply { quiet = true }
        assertTrue(e.onTurns(route).isEmpty())                       // 1.2km: no early briefing
        val near = listOf(route[0].copy(gapM = 350.0)) + route.drop(1)
        assertEquals(1, e.onTurns(near).size)                        // 350m: underpass reminder
        val combo = listOf(route[2].copy(gapM = 150.0), Turn("TURN_RIGHT", 50.0, ""), route[3])
        assertTrue(e.onTurns(combo).isEmpty())                       // plain back-to-back rights: skipped
    }
}
