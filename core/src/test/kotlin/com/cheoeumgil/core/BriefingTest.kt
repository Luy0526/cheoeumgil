package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the TBT list the emulator returned for the 판교테크노밸리 route (2.3km). */
class BriefingTest {
    private val route = listOf(
        Turn("TURN_LEFT", 80.0, "기업지원허브 방면"),
        Turn("TURN_RIGHT", 380.0, "판교IC 방면"),
        Turn("TURN_AVOID_UNDER_R", 730.0, "판교IC 방면"),
        Turn("TURN_RIGHT", 820.0, "경기창조경제혁신센터 방면"),
        Turn("TURN_RIGHT", 200.0, "이름 없는 도로"),
        Turn("TURN_RIGHT", 50.0, "이름 없는 도로"),
        Turn("TURN_ARRIVE", 10.0, "도착"),
    )

    @Test
    fun briefsHardSectionEarlyThenNearOnceEach() {
        val e = BriefingEngine()
        // plain left/right turns are skipped; the underpass bypass 1.2km ahead is briefed early
        assertEquals(
            listOf("1.2킬로미터 앞 판교IC 방면 지하차도 옆길이에요. 지하차도와 갈라지니, 지하차도가 아닌 오른쪽 옆길인지 미리 확인하세요."),
            e.onTurns(route),
        )
        assertTrue(e.onTurns(route).isEmpty()) // same list again: nothing repeated

        // car passed the first two turns, underpass is 350m away -> short reminder
        val later = listOf(route[2].copy(gapM = 350.0)) + route.drop(3)
        assertEquals(listOf("곧 판교IC 방면 지하차도 옆길이에요. 지하차도와 갈라지니, 지하차도가 아닌 오른쪽 옆길인지 미리 확인하세요."), e.onTurns(later))
        // one briefing per update: the back-to-back right turns come on the next update, once
        assertEquals(
            listOf("1.2킬로미터 앞 경기창조경제혁신센터 방면 우회전이에요. 바로 200미터 뒤에 우회전이 이어지니, 미리 우회전할 준비를 하세요."),
            e.onTurns(later),
        )
        assertTrue(e.onTurns(later).isEmpty()) // the chained second turn was covered by the combo
    }

    @Test
    fun chainedTurnsCountAsOneSection() {
        val sections = BriefingEngine().hardSections(route, 5_000.0)
        assertEquals(listOf("지하차도 옆길", "우회전 후 바로 우회전"), sections.map { it.title })
    }

    @Test
    fun backToBackTurnsAreACombo() {
        val e = BriefingEngine()
        val end = listOf(route[4].copy(gapM = 300.0)) + route.drop(5)
        assertEquals(
            listOf("곧 우회전이에요. 바로 50미터 뒤에 우회전이 이어지니, 미리 우회전할 준비를 하세요."),
            e.onTurns(end),
        )
    }

    @Test
    fun skippingStraightToNearDoesNotBriefEarlyLater() {
        val e = BriefingEngine()
        val near = listOf(Turn("TURN_DEVIDE_MERGE", 200.0, ""), Turn("TURN_ARRIVE", 900.0, "도착"))
        assertEquals(1, e.onTurns(near).size)
        assertTrue(e.onTurns(listOf(near[0].copy(gapM = 100.0), near[1])).isEmpty())
    }

    @Test
    fun cautionsOnlyCloseAndKnownAndOnce() {
        val e = BriefingEngine()
        val pts = listOf(Caution("a", "SCHOOL_ZONE", 600), Caution("b", "ACCIDENT_PRONE_AREA", 3000), Caution("c", "SPEED_BUMP", 100))
        assertEquals(listOf("600미터 앞 어린이 보호구역이에요. 시속 30킬로미터 이하로 천천히 가세요."), e.onCautions(pts))
        assertTrue(e.onCautions(pts).isEmpty())
    }

    @Test
    fun distanceWording() {
        assertEquals("1.2킬로미터", BriefingEngine.distance(1190.0))
        assertEquals("2킬로미터", BriefingEngine.distance(2010.0))
        assertEquals("350미터", BriefingEngine.distance(340.0))
        assertEquals("50미터", BriefingEngine.distance(10.0))
    }

    @Test
    fun routeSummaryOnceAndNotOnFamiliarRoutes() {
        val e = BriefingEngine()
        assertEquals("이번 경로에 어려운 구간이 2곳 있어요.", e.summary(route)) // underpass + right-right combo
        assertEquals(null, e.summary(route))
        assertEquals(null, BriefingEngine().apply { quiet = true }.summary(route))
    }

    @Test
    fun tipsNeverDirectALane() {
        // TBT has no lane data, so no tip may tell the driver which lane to be in
        val types = listOf("TURN_LEFT", "TURN_RIGHT", "TURN_KEEP_R", "TURN_SHARP_L", "TURN_UTURN", "TURN_ENTER_IC_R", "TURN_EXIT_IC_R",
            "TURN_ENTER_UNDER_L", "TURN_AVOID_UNDER_R", "TURN_ENTER_OVER_L", "TURN_AVOID_OVER_R", "TURN_ENTER_ROUNDABOUT_09",
            "TURN_EXIT_ROUNDABOUT", "TURN_DEVIDE_MERGE", "TURN_TOLL", "TURN_RESTAREA", "TURN_OTHER")
        for (t in types) {
            val tip = BriefingEngine.action(t).second
            assertTrue("$t: $tip", listOf("차로를 유지", "차로로", "1차로").none { it in tip })
        }
    }
}
