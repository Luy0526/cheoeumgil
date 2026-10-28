package com.cheoeumgil.core

import kotlin.math.roundToInt

/** One upcoming maneuver from NaviHelper TBT. [gapM] = distance from the previous item (first item: from the car). */
data class Turn(val type: String, val gapM: Double, val description: String)

/** One caution point from NaviHelper. */
data class Caution(val id: String, val type: String, val remainM: Int)

/** A hard section [aheadM] metres away; [next] is set when another maneuver follows right after. */
data class Section(val turn: Turn, val aheadM: Double, val next: Turn?) {
    val title get() = BriefingEngine.action(turn.type).first + (next?.let { " 후 바로 ${BriefingEngine.action(it.type).first}" } ?: "")
}

/**
 * Decides which upcoming sections are hard, when to brief them, and what to say.
 * Pure Kotlin (no Android) so it can be unit-tested with recorded navi data.
 */
class BriefingEngine(
    private val earlyM: Int = 1500,   // first briefing distance
    private val nearM: Int = 400,     // reminder distance
    private val comboGapM: Int = 300, // a following maneuver this close makes a "combo"
    private val cautionM: Int = 1000,
) {
    private val spoken = mutableSetOf<String>()
    private var summarized = false

    /** Familiar route: only the near reminder for genuinely hard sections (no early briefings, no plain combos). */
    var quiet = false

    /**
     * Returns the phrase to speak now (at most one per update, nearest first, so the driver
     * never hears a pile-up); each section is briefed at most once per stage.
     */
    fun onTurns(turns: List<Turn>): List<String> {
        var ahead = 0.0
        for ((i, t) in turns.withIndex()) {
            ahead += t.gapM
            if (ahead > earlyM) break
            val next = turns.getOrNull(i + 1)?.takeIf { it.gapM <= comboGapM && it.type != "TURN_ARRIVE" }
            if (!isHard(t.type) && next == null) continue
            val near = ahead <= nearM
            if (quiet && (!near || !isHard(t.type))) continue
            val stage = if (near) "near" else "early"
            if (!spoken.add("${key(turns, i)}|$stage")) continue
            if (near) spoken.add("${key(turns, i)}|early")
            // a combo briefing already announces the next turn
            if (next != null) {
                spoken.add("${key(turns, i + 1)}|$stage")
                if (near) spoken.add("${key(turns, i + 1)}|early")
            }
            return listOf(phrase(t, ahead, next, near))
        }
        return emptyList()
    }

    /** Once per route, on the first TBT list: how many hard sections lie ahead. Skipped on familiar routes. */
    fun summary(turns: List<Turn>): String? {
        if (summarized || turns.isEmpty()) return null
        summarized = true
        val n = hardSections(turns, Double.MAX_VALUE).size
        return if (quiet || n == 0) null else "이번 경로에 어려운 구간이 ${n}곳 있어요."
    }

    // TBT drops passed items from the front, so position-from-the-end is a stable id
    private fun key(turns: List<Turn>, i: Int) = "${turns.size - i}|${turns[i].type}|${turns[i].description}"

    fun onCautions(points: List<Caution>): List<String> =
        points.filter { it.remainM <= cautionM && it.type in CAUTION_NAMES && spoken.add("caution|${it.id}") }
            .map { "${distance(it.remainM.toDouble())} 앞 ${CAUTION_NAMES[it.type]}이에요. ${cautionTip(it.type)}" }

    /** 헤맴 지도 hotspots ahead (nearest first, from [hotspotsAhead]); one briefing per update, once per hotspot. */
    fun onHotspots(ahead: List<Pair<Hotspot, Double>>): List<String> =
        ahead.firstOrNull { (h, d) -> d <= earlyM && spoken.add("hotspot|${h.spot}") }
            ?.let { (h, d) -> listOf("${distance(d)} 앞은 다른 운전자들이 ${h.main.reason} 구간이에요. 미리 차로와 속도를 준비하세요.") }
            ?: emptyList()

    /** Hard sections (with distance ahead) within [rangeM], nearest first. */
    fun hardSections(turns: List<Turn>, rangeM: Double = earlyM.toDouble()): List<Section> {
        val out = mutableListOf<Section>()
        var ahead = 0.0
        var covered = -1 // index already announced as the second half of a combo
        for ((i, t) in turns.withIndex()) {
            ahead += t.gapM
            if (ahead > rangeM) break
            if (i == covered) continue
            val next = turns.getOrNull(i + 1)?.takeIf { it.gapM <= comboGapM && it.type != "TURN_ARRIVE" }
            if (isHard(t.type) || next != null) {
                out += Section(t, ahead, next)
                if (next != null) covered = i + 1
            }
        }
        return out
    }

    /** The next hard section within [earlyM], for the on-screen card. */
    fun nextHard(turns: List<Turn>) = hardSections(turns).firstOrNull()

    companion object {
        private val EASY = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_LEFT", "TURN_RIGHT", "TURN_WAY", "TURN_ARRIVE", "TURN_TUNNEL")

        fun isHard(type: String) = type !in EASY

        fun distance(m: Double): String =
            if (m >= 1000) "${((m / 100).roundToInt() / 10.0).toString().removeSuffix(".0")}킬로미터"
            else "${maxOf(50, (m / 50).roundToInt() * 50)}미터"

        private fun side(type: String) = when {
            type.endsWith("_R") -> "오른쪽"
            type.endsWith("_L") -> "왼쪽"
            else -> "가운데"
        }

        /** (what it is, what to do) */
        fun action(type: String): Pair<String, String> {
            val s = side(type)
            return when {
                type == "TURN_LEFT" -> "좌회전" to "미리 왼쪽 차로로 옮겨 두세요."
                type == "TURN_RIGHT" -> "우회전" to "미리 오른쪽 차로로 옮겨 두세요."
                type.startsWith("TURN_KEEP") -> "갈림길 ${s} 길" to "갈림길 직전에 급하게 바꾸지 않도록 지금 ${s} 차로를 유지하세요."
                type.startsWith("TURN_SHARP") -> "급하게 꺾이는 ${s} 길" to "속도를 충분히 줄이세요."
                type == "TURN_UTURN" -> "유턴" to "1차로로 미리 이동하고 유턴 신호를 확인하세요."
                type.startsWith("TURN_ENTER_IC") -> "나들목 진입" to "진입로가 짧을 수 있어요. 속도를 맞춰 들어가세요."
                type.startsWith("TURN_EXIT_IC") -> "나들목 출구" to "${s} 끝 차로로 미리 옮겨 두세요."
                type.startsWith("TURN_ENTER_UNDER") -> "지하차도 진입" to "지하차도로 들어가는 ${s} 차로를 유지하세요."
                type.startsWith("TURN_AVOID_UNDER") -> "지하차도 옆길" to "지하차도로 들어가지 않도록 ${s} 차로를 유지하세요."
                type.startsWith("TURN_ENTER_OVER") -> "고가차도 진입" to "고가차도로 올라가는 ${s} 차로를 유지하세요."
                type.startsWith("TURN_AVOID_OVER") -> "고가차도 옆길" to "고가차도로 올라가지 않도록 ${s} 차로를 유지하세요."
                type.startsWith("TURN_ENTER_ROUNDABOUT") ->
                    "회전교차로 ${type.takeLast(2).trimStart('0')}시 방향" to "진입 전에 속도를 줄이고, 먼저 돌고 있는 차를 보내세요."
                type == "TURN_EXIT_ROUNDABOUT" -> "회전교차로 출구" to "나가기 전에 오른쪽 깜빡이를 켜세요."
                type == "TURN_DEVIDE_MERGE" -> "합류 구간" to "깜빡이를 켜고 속도를 맞춰 합류하세요."
                type == "TURN_TOLL" -> "요금소" to "하이패스 차로인지 미리 확인하세요."
                type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> "휴게소 진입로" to "오른쪽 끝 차로로 미리 옮겨 두세요."
                else -> "방향 전환" to "미리 차로를 확인하세요."
            }
        }

        fun phrase(t: Turn, aheadM: Double, next: Turn?, near: Boolean): String {
            val (what, tip) = action(t.type)
            val where = t.description.takeIf { it.isNotBlank() && it != "이름 없는 도로" }?.let { "$it " } ?: ""
            val head = if (near) "곧 $where$what" else "${distance(aheadM)} 앞 $where$what"
            val combo = next?.let { " 바로 ${distance(it.gapM)} 뒤에 ${action(it.type).first}이 이어지니, ${action(it.type).second}" } ?: " $tip"
            return "${head}이에요.$combo"
        }

        val CAUTION_NAMES = mapOf(
            "ACCIDENT_PRONE_AREA" to "사고가 잦은 구간",
            "NARROW_ROAD" to "길이 좁아지는 구간",
            "HIGHWAY_CONSTRUCTION" to "공사 구간",
            "SCHOOL_ZONE" to "어린이 보호구역",
            "SLIPPERY_ROAD_ALERT" to "미끄러운 길",
            "DOWNHILL_CAUTION_AREA" to "급경사 내리막",
            "FALLING_ROCK_AREA" to "낙석 주의 구간",
            "FOG_AREA" to "안개가 잦은 구간",
            "RAILROAD_CROSSING" to "철길 건널목",
            "WILDLIFE_CROSSING" to "야생동물 출몰 구간",
            "ROAD_DAMAGE_ALERT" to "도로 파손 구간",
            "WRONG_WAY_DRIVING_ALERT" to "역주행 차량 경고 구간",
        )

        private fun cautionTip(type: String) = when (type) {
            "SCHOOL_ZONE" -> "시속 30킬로미터 이하로 천천히 가세요."
            "RAILROAD_CROSSING" -> "건너기 전에 일단 멈추고 좌우를 확인하세요."
            "WRONG_WAY_DRIVING_ALERT" -> "앞 차와 거리를 넉넉히 두세요."
            else -> "미리 속도를 줄이세요."
        }
    }
}
