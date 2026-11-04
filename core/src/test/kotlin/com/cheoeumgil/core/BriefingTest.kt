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
            listOf("1.2킬로미터 앞 판교IC 방면 지하차도 옆길이에요. 입구와 옆길이 붙어 있어 직전엔 옮기기 어려우니, 지금부터 오른쪽 옆길로 갈 준비를 하세요."),
            e.onTurns(route),
        )
        assertTrue(e.onTurns(route).isEmpty()) // same list again: nothing repeated

        // car passed the first two turns, underpass is 350m away -> short reminder
        val later = listOf(route[2].copy(gapM = 350.0)) + route.drop(3)
        assertEquals(listOf("곧 판교IC 방면 지하차도 옆길이에요. 입구와 옆길이 붙어 있어 직전엔 옮기기 어려우니, 지금부터 오른쪽 옆길로 갈 준비를 하세요."), e.onTurns(later))
        // the back-to-back plain right turns after it are the navi's own job (it announces 연속 회전)
        assertTrue(e.onTurns(later).isEmpty())
    }

    @Test
    fun onlyCombosWithAHardManeuverCount() {
        assertEquals(listOf("지하차도 옆길"), BriefingEngine().hardSections(route, 5_000.0).map { it.title })
        val bypassThenRight = listOf(Turn("TURN_AVOID_UNDER_R", 300.0, ""), Turn("TURN_RIGHT", 100.0, ""), Turn("TURN_ARRIVE", 500.0, "도착"))
        assertEquals(listOf("지하차도 옆길 후 바로 우회전"), BriefingEngine().hardSections(bypassThenRight).map { it.title })
        assertEquals(
            listOf("곧 지하차도 옆길이에요. 바로 100미터 뒤에 우회전이 이어져 그 사이엔 옮기기 어려우니, 지금부터 준비하세요."),
            BriefingEngine().onTurns(bypassThenRight),
        )
    }

    @Test
    fun aJamOnTheApproachIsTheReason() {
        // route traffic from the emulator: offsets count from the route start (2.3km); the car has come 30m
        val e = BriefingEngine().apply { routeM = 2_300.0; jams = listOf(Jam(900.0, 250.0, 14.0)) }
        assertEquals(
            listOf("1.2킬로미터 앞 판교IC 방면 지하차도 옆길이에요. 그 앞이 막혀 있어 직전엔 끼어들기 더 어려우니, 지금부터 오른쪽 옆길로 갈 준비를 하세요."),
            e.onTurns(route),
        )
        assertEquals("그 앞 250미터가 평균 시속 14킬로미터로 막혀 있어서, 직전엔 끼어들기 더 어려워요.",
            VoiceQuery.why(e.hardSections(route).single()))
        // a back-to-back section keeps saying what follows
        assertEquals("바로 100미터 뒤에 나들목 진입이 이어지고, 그 앞이 막혀 있어 끼어들기 더 어려우니, 지금부터 준비하세요.",
            Section(Turn("TURN_RIGHT", 750.0, ""), 750.0, Turn("TURN_ENTER_IC_C", 110.0, ""), Jam(700.0, 80.0, 17.0)).tip)
        // a jam past the section, or with the route length unknown, changes nothing
        assertEquals(null, BriefingEngine().apply { routeM = 2_300.0; jams = listOf(Jam(1_500.0, 100.0, 15.0)) }.nextHard(route)?.jam)
        assertEquals(null, BriefingEngine().apply { jams = listOf(Jam(900.0, 250.0, 14.0)) }.nextHard(route)?.jam)
    }

    @Test
    fun skippingStraightToNearDoesNotBriefEarlyLater() {
        val e = BriefingEngine()
        val near = listOf(Turn("TURN_DEVIDE_MERGE", 200.0, ""), Turn("TURN_ARRIVE", 900.0, "도착"))
        assertEquals(1, e.onTurns(near).size)
        assertTrue(e.onTurns(listOf(near[0].copy(gapM = 100.0), near[1])).isEmpty())
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
        assertEquals("이번 경로에 어려운 구간이 1곳 있어요.", e.summary(route)) // the underpass; plain right-right is not counted
        assertEquals(null, e.summary(route))
        assertEquals(null, BriefingEngine().apply { quiet = true }.summary(route))
    }

    @Test
    fun tipsNeverDirectALane() {
        // TBT has no lane data, so no tip may tell the driver which lane to be in or to move over
        val types = listOf("TURN_LEFT", "TURN_RIGHT", "TURN_KEEP_R", "TURN_SHARP_L", "TURN_UTURN", "TURN_ENTER_IC_R", "TURN_EXIT_IC_R",
            "TURN_ENTER_UNDER_L", "TURN_AVOID_UNDER_R", "TURN_ENTER_OVER_L", "TURN_AVOID_OVER_R", "TURN_ENTER_ROUNDABOUT_09",
            "TURN_EXIT_ROUNDABOUT", "TURN_DEVIDE_MERGE", "TURN_TOLL", "TURN_RESTAREA", "TURN_OTHER")
        // nor repeat the navi's own direction ("우회전할 준비")
        val jam = Jam(0.0, 500.0, 10.0)
        val tips = types.flatMap { t ->
            val turn = Turn(t, 500.0, "")
            listOf(BriefingEngine.action(t).second, Section(turn, 500.0, null, jam).tip,
                Section(turn, 500.0, Turn("TURN_RIGHT", 100.0, "")).tip, Section(turn, 500.0, Turn("TURN_RIGHT", 100.0, ""), jam).tip)
        }
        for (tip in tips) {
            assertTrue(tip, listOf("차로를 유지", "차로로", "1차로", "붙어 두", "옮기세요", "길인지", "회전할 준비").none { it in tip })
        }
    }

    @Test
    fun particlesFollowTheLastSyllable() {
        assertEquals("1.5킬로미터 앞 요금소예요. 하이패스 차로인지 미리 확인하세요.",
            BriefingEngine.phrase(Section(Turn("TURN_TOLL", 1500.0, ""), 1500.0, null), near = false))
        assertEquals("곧 나들목 출구예요. 바로 200미터 뒤에 요금소가 이어져 그 사이엔 옮기기 어려우니, 지금부터 준비하세요.",
            BriefingEngine.phrase(Section(Turn("TURN_EXIT_IC_R", 400.0, ""), 400.0, Turn("TURN_TOLL", 200.0, "")), near = true))
    }
}
