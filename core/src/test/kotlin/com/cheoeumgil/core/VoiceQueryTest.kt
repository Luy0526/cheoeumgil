package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceQueryTest {
    private val underpass = Section(Turn("TURN_AVOID_UNDER_R", 1190.0, "판교IC 방면"), 1190.0, null)
    private val combo = Section(Turn("TURN_RIGHT", 300.0, ""), 300.0, Turn("TURN_RIGHT", 50.0, ""))

    @Test
    fun classifiesTypicalQuestions() {
        assertEquals(Ask.NEXT, VoiceQuery.classify("다음 어려운 구간 어디야"))
        assertEquals(Ask.WHY, VoiceQuery.classify("거기 왜 어려워"))
        assertEquals(Ask.REPEAT, VoiceQuery.classify("다음 구간 다시 말해줘"))
        assertEquals(Ask.COUNT, VoiceQuery.classify("어려운 구간 몇 개 남았어"))
        assertEquals(Ask.QUIET, VoiceQuery.classify("안내 좀 줄여줘"))
        assertEquals(Ask.DETAIL, VoiceQuery.classify("자세히 알려줘"))
        assertEquals(Ask.UNKNOWN, VoiceQuery.classify("음악 틀어줘"))
    }

    @Test
    fun answersFromCurrentRouteState() {
        assertEquals("1.2킬로미터 앞 지하차도 옆길이에요. 지하차도로 들어가지 않도록 오른쪽 차로를 유지하세요.",
            VoiceQuery.answer(Ask.NEXT, underpass, 3, null, null).text)
        assertEquals("입구와 옆길이 바로 붙어 있어서, 차로를 늦게 고르면 원하지 않는 길로 들어가기 쉬워요.",
            VoiceQuery.answer(Ask.WHY, underpass, 3, null, null).text)
        assertEquals("회전 두 번이 50미터 간격으로 붙어 있어서, 첫 회전 뒤에 차로를 바꿀 시간이 거의 없어요.",
            VoiceQuery.answer(Ask.WHY, combo, 1, null, null).text)
        assertEquals("5킬로미터 안에 어려운 구간이 3곳 있어요.", VoiceQuery.answer(Ask.COUNT, underpass, 3, null, null).text)
        assertEquals("아직 드린 안내가 없어요.", VoiceQuery.answer(Ask.REPEAT, null, 0, null, null).text)
        assertEquals("1.5킬로미터 안에는 어려운 구간이 없어요.", VoiceQuery.answer(Ask.NEXT, null, 0, null, null).text)
    }

    @Test
    fun hotspotWhenNoTurnSectionAndQuietSwitches() {
        val h = Hotspot(Spot(37.0, 127.0), 4, StruggleKind.SUDDEN_BRAKE) to 600.0
        assertEquals("600미터 앞에 다른 운전자들이 급하게 브레이크를 밟은 구간이 있어요.", VoiceQuery.answer(Ask.NEXT, null, 0, h, null).text)
        assertEquals("그곳은 서로 다른 주행 4번에서 급하게 브레이크를 밟았어요. 속도를 미리 줄이세요.", VoiceQuery.answer(Ask.WHY, null, 0, h, null).text)
        assertEquals(true, VoiceQuery.answer(Ask.QUIET, null, 0, null, null).setQuiet)
        assertEquals(false, VoiceQuery.answer(Ask.DETAIL, null, 0, null, null).setQuiet)
        assertNull(VoiceQuery.answer(Ask.NEXT, null, 0, null, null).setQuiet)
    }
}
