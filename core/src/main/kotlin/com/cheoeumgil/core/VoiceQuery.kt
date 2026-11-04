package com.cheoeumgil.core

/** What the driver asked. On-device STT returns plain text, so this is keyword matching. */
enum class Ask { NEXT, WHY, REPEAT, COUNT, QUIET, DETAIL, UNKNOWN }

/** The spoken reply, and the quiet-mode switch it requests (null = unchanged). */
data class Answer(val text: String, val setQuiet: Boolean? = null)

object VoiceQuery {
    // order matters: "다음 구간 다시 말해줘" is a repeat, "다음 구간 왜 어려워" is a why
    private val rules = listOf(
        Ask.REPEAT to listOf("다시", "뭐라고", "한번더"),
        Ask.WHY to listOf("왜", "이유"),
        Ask.COUNT to listOf("몇개", "몇군데", "몇곳", "남은", "남았"),
        Ask.QUIET to listOf("조용", "줄여", "그만"),
        Ask.DETAIL to listOf("자세히", "다알려", "전부알려"),
        Ask.NEXT to listOf("다음", "어디", "어려운", "뭐있"),
    )

    fun classify(text: String): Ask {
        val t = text.replace(" ", "")
        return rules.firstOrNull { (_, words) -> words.any { it in t } }?.first ?: Ask.UNKNOWN
    }

    /**
     * [next] = nearest hard section, [aheadCount] = hard sections within 5km,
     * [hotspot] = nearest 헤맴 구간 ahead, [lastBriefing] = the last briefing spoken.
     */
    fun answer(ask: Ask, next: Section?, aheadCount: Int, hotspot: Pair<Hotspot, Double>?, lastBriefing: String?): Answer =
        when (ask) {
            Ask.NEXT -> Answer(
                next?.let { BriefingEngine.phrase(it, near = false) }
                    ?: hotspot?.let { (h, d) -> "${BriefingEngine.distance(d)} 앞에 서로 다른 주행 ${h.trips}번에서 ${h.main.reason} 구간이 있어요." }
                    ?: "1.5킬로미터 안에는 어려운 구간이 없어요."
            )
            Ask.WHY -> Answer(
                next?.let(::why)
                    ?: hotspot?.let { (h, _) -> "그곳은 서로 다른 주행 ${h.trips}번에서 ${reasonSentence(h.main)}" }
                    ?: "지금은 특별히 어려운 구간이 없어요."
            )
            Ask.REPEAT -> Answer(lastBriefing ?: "아직 드린 안내가 없어요.")
            Ask.COUNT -> Answer(if (aheadCount == 0) "5킬로미터 안에는 어려운 구간이 없어요." else "5킬로미터 안에 어려운 구간이 ${aheadCount}곳 있어요.")
            Ask.QUIET -> Answer("알겠어요. 꼭 필요한 안내만 드릴게요.", setQuiet = true)
            Ask.DETAIL -> Answer("알겠어요. 어려운 구간을 미리 자세히 알려드릴게요.", setQuiet = false)
            Ask.UNKNOWN -> Answer("다음 어려운 구간, 왜 어려운지, 다시 말해줘처럼 물어봐 주세요.")
        }

    private fun reasonSentence(kind: StruggleKind) = when (kind) {
        StruggleKind.REROUTE -> "경로를 벗어났어요."
        StruggleKind.SUDDEN_BRAKE -> "급하게 브레이크를 밟았어요."
        StruggleKind.SIGNAL_HESITATION -> "깜빡이를 켜고 한참 못 끼어들었어요."
        StruggleKind.CRUISE_DROP -> "크루즈가 꺼졌어요."
    } + " " + kind.tip

    /** Why a section is hard, in one sentence a driver can take in. */
    fun why(s: Section): String {
        s.jam?.let { return "그 앞 ${BriefingEngine.subject(BriefingEngine.distance(it.lengthM))} 평균 시속 ${it.kmh.toInt()}킬로미터로 막혀 있어서, 직전엔 끼어들기 더 어려워요." }
        if (s.next != null) return "회전 두 번이 ${BriefingEngine.distance(s.next.gapM)} 간격으로 붙어 있어서, 첫 회전 뒤에 차로를 바꿀 시간이 거의 없어요."
        val t = s.turn.type
        return when {
            t.startsWith("TURN_AVOID_UNDER") || t.startsWith("TURN_AVOID_OVER") ->
                "입구와 옆길이 바로 붙어 있어서, 차로를 늦게 고르면 원하지 않는 길로 들어가기 쉬워요."
            t.startsWith("TURN_ENTER_UNDER") || t.startsWith("TURN_ENTER_OVER") ->
                "진입로가 갈라지는 지점이 짧아서, 미리 차로를 잡지 않으면 놓치기 쉬워요."
            t.startsWith("TURN_KEEP") -> "갈림길 직전에 차로를 바꾸면 위험해서, 미리 자리를 잡아야 해요."
            t.startsWith("TURN_ENTER_IC") || t.startsWith("TURN_EXIT_IC") -> "나들목은 진입로와 출구가 짧아서 속도와 차로를 미리 맞춰야 해요."
            t.startsWith("TURN_ENTER_ROUNDABOUT") -> "회전교차로는 이미 돌고 있는 차가 우선이라, 진입 전에 속도를 줄여야 해요."
            t == "TURN_DEVIDE_MERGE" -> "합류 구간은 옆 차로 차와 속도를 맞춰야 해서 처음 가면 긴장되기 쉬워요."
            t == "TURN_UTURN" -> "유턴은 신호와 맞은편 차를 함께 봐야 해서, 유턴할 수 있는 곳을 미리 알아 두는 게 좋아요."
            t.startsWith("TURN_SHARP") -> "길이 급하게 꺾여서 속도를 충분히 줄이지 않으면 위험해요."
            t == "TURN_TOLL" -> "요금소는 하이패스 차로와 일반 차로가 갈라져서 미리 골라야 해요."
            else -> "처음 가는 길에서 놓치기 쉬운 구간이에요."
        }
    }
}
