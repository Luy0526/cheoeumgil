package com.cheoeumgil.core

import kotlin.math.roundToInt

/** One upcoming maneuver from NaviHelper TBT. [gapM] = distance from the previous item (first item: from the car). */
data class Turn(val type: String, val gapM: Double, val description: String)

/** A congested stretch from NaviHelper route traffic: [offsetM] from the route start, [kmh] average speed. */
data class Jam(val offsetM: Double, val lengthM: Double, val kmh: Double)

/**
 * A hard section [aheadM] metres away; [next] is set when another maneuver follows right after,
 * [jam] when traffic is backed up on the approach (merging gets harder there).
 */
data class Section(val turn: Turn, val aheadM: Double, val next: Turn?, val jam: Jam? = null) {
    val title get() = BriefingEngine.action(turn.type).first + (next?.let { " 후 바로 ${BriefingEngine.action(it.type).first}" } ?: "")

    /** Why to get ready now. A jam replaces the usual reason; tips without a "지금부터" part just get it in front. */
    val tip: String get() {
        next?.let {
            val combo = "바로 ${BriefingEngine.distance(it.gapM)} 뒤에 ${BriefingEngine.subject(BriefingEngine.action(it.type).first)} 이어"
            return if (jam == null) "${combo}져 그 사이엔 옮기기 어려우니, 지금부터 준비하세요."
            else "${combo}지고, 그 앞이 막혀 있어 끼어들기 더 어려우니, 지금부터 준비하세요."
        }
        val base = BriefingEngine.action(turn.type).second
        if (jam == null) return base
        val prepare = base.substringAfter(", 지금부터 ", "")
        return if (prepare.isEmpty()) "그 앞이 막혀 있어요. $base" else "그 앞이 막혀 있어 직전엔 끼어들기 더 어려우니, 지금부터 $prepare"
    }
}

/**
 * Decides which upcoming sections are hard, when to brief them, and what to say.
 * Pure Kotlin (no Android) so it can be unit-tested with recorded navi data.
 */
class BriefingEngine(
    private val earlyM: Int = 1500,   // first briefing distance
    private val nearM: Int = 400,     // reminder distance
    private val comboGapM: Int = 300, // a following maneuver this close makes a "combo"
    private val jamApproachM: Int = 300, // traffic this close before a section makes it harder
) {
    private val spoken = mutableSetOf<String>()
    private var summarized = false

    /** Familiar route: only the near reminder for genuinely hard sections (no early briefings, no plain combos). */
    var quiet = false

    /** Route length (from route start) and its congested stretches, for [Section.jam]; 0 = unknown, jams ignored. */
    var routeM = 0.0
    var jams = listOf<Jam>()

    /**
     * Returns the phrase to speak now (at most one per update, nearest first, so the driver
     * never hears a pile-up); each section is briefed at most once per stage.
     */
    fun onTurns(turns: List<Turn>): List<String> {
        var ahead = 0.0
        for ((i, t) in turns.withIndex()) {
            ahead += t.gapM
            if (ahead > earlyM) break
            val next = comboNext(turns, i)
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
            return listOf(phrase(Section(t, ahead, next, jamAt(turns, ahead)), near))
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

    /**
     * Two maneuvers this close make a combo, but only when one of them is a hard one: the navi already
     * announces plain back-to-back turns itself.
     */
    private fun comboNext(turns: List<Turn>, i: Int) = turns.getOrNull(i + 1)
        ?.takeIf { it.gapM <= comboGapM && it.type != "TURN_ARRIVE" && (isHard(turns[i].type) || isHard(it.type)) }

    /**
     * The jam on the approach to a section [aheadM] away. TBT lists the whole remaining route, so its sum is
     * the distance left and route length minus that is how far we have come.
     * ponytail: assumes jam offsets count from the route start (matches the emulator at the start, where the car
     * never moves); recheck on a real drive.
     */
    private fun jamAt(turns: List<Turn>, aheadM: Double): Jam? {
        if (routeM <= 0) return null
        val travelled = routeM - turns.sumOf { it.gapM }
        return jams.firstOrNull { j -> val start = j.offsetM - travelled; start <= aheadM && start + j.lengthM >= aheadM - jamApproachM }
    }

    /** 헤맴 지도 hotspots ahead (nearest first, from [hotspotsAhead]); one briefing per update, once per hotspot. */
    fun onHotspots(ahead: List<Pair<Hotspot, Double>>): List<String> =
        ahead.firstOrNull { (h, d) -> d <= earlyM && spoken.add("hotspot|${h.spot}") }
            ?.let { (h, d) -> listOf("${distance(d)} 앞은 서로 다른 주행 ${h.trips}번에서 ${h.main.reason} 구간이에요. ${h.main.tip}") }
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
            val next = comboNext(turns, i)
            if (isHard(t.type) || next != null) {
                out += Section(t, ahead, next, jamAt(turns, ahead))
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

        private fun hasBatchim(w: String) = w.lastOrNull()?.let { it in '가'..'힣' && (it - '가') % 28 != 0 } ?: false

        /** "요금소" -> "요금소예요", "갈림길" -> "갈림길이에요". */
        fun copula(w: String) = w + if (hasBatchim(w)) "이에요" else "예요"

        /** "요금소" -> "요금소가", "우회전" -> "우회전이". */
        fun subject(w: String) = w + if (hasBatchim(w)) "이" else "가"

        private fun side(type: String) = when {
            type.endsWith("_R") -> "오른쪽"
            type.endsWith("_L") -> "왼쪽"
            else -> "가운데"
        }

        /**
         * (what it is, what to do). The navi already says where to go; tips say why to get ready early
         * (a late lane change is hard there) and point at the road to take. TBT gives the turn's side,
         * not lane data, so they never name, hold or order a lane.
         */
        fun action(type: String): Pair<String, String> {
            val s = side(type)
            return when {
                // plain turns are never briefed alone, only named as half of a combo (whose tip is used)
                type == "TURN_LEFT" -> "좌회전" to ""
                type == "TURN_RIGHT" -> "우회전" to ""
                type.startsWith("TURN_KEEP") -> "갈림길 ${s} 길" to "갈림길 직전엔 옮기기 어려우니, 지금부터 ${s} 길로 갈 준비를 하세요."
                type.startsWith("TURN_SHARP") -> "급하게 꺾이는 ${s} 길" to "속도를 충분히 줄이세요."
                type == "TURN_UTURN" -> "유턴" to "유턴할 수 있는 곳과 신호를 미리 확인하세요."
                type.startsWith("TURN_ENTER_IC") -> "나들목 진입" to "진입로가 짧을 수 있어요. 속도를 맞춰 들어가세요."
                type.startsWith("TURN_EXIT_IC") -> "나들목 출구" to "출구 직전엔 끼어들기 어려우니, 지금부터 ${s} 출구로 나갈 준비를 하세요."
                type.startsWith("TURN_ENTER_UNDER") -> "지하차도 진입" to "옆길과 바로 갈라져 직전엔 옮기기 어려우니, 지금부터 지하차도 쪽 ${s} 길로 갈 준비를 하세요."
                type.startsWith("TURN_AVOID_UNDER") -> "지하차도 옆길" to "입구와 옆길이 붙어 있어 직전엔 옮기기 어려우니, 지금부터 ${s} 옆길로 갈 준비를 하세요."
                type.startsWith("TURN_ENTER_OVER") -> "고가차도 진입" to "옆길과 바로 갈라져 직전엔 옮기기 어려우니, 지금부터 고가차도 쪽 ${s} 길로 갈 준비를 하세요."
                type.startsWith("TURN_AVOID_OVER") -> "고가차도 옆길" to "입구와 옆길이 붙어 있어 직전엔 옮기기 어려우니, 지금부터 ${s} 옆길로 갈 준비를 하세요."
                type.startsWith("TURN_ENTER_ROUNDABOUT") ->
                    "회전교차로 ${type.takeLast(2).trimStart('0')}시 방향" to "진입 전에 속도를 줄이고, 먼저 돌고 있는 차를 보내세요."
                type == "TURN_EXIT_ROUNDABOUT" -> "회전교차로 출구" to "나가기 전에 오른쪽 깜빡이를 켜세요."
                type == "TURN_DEVIDE_MERGE" -> "합류 구간" to "깜빡이를 켜고 속도를 맞춰 합류하세요."
                type == "TURN_TOLL" -> "요금소" to "하이패스 차로인지 미리 확인하세요."
                type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> "휴게소 진입로" to "진입로를 놓치지 않게 미리 속도를 줄이세요."
                else -> "방향 전환" to "안내 경로를 미리 확인하세요."
            }
        }

        fun phrase(s: Section, near: Boolean): String {
            val what = action(s.turn.type).first
            val where = s.turn.description.takeIf { it.isNotBlank() && it != "이름 없는 도로" }?.let { "$it " } ?: ""
            val head = if (near) "곧 $where$what" else "${distance(s.aheadM)} 앞 $where$what"
            return "${copula(head)}. ${s.tip}"
        }
    }
}
