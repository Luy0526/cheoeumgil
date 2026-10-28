package com.cheoeumgil.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Intent
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.app.Activity
import com.cheoeumgil.core.BriefingEngine
import com.cheoeumgil.core.Caution
import com.cheoeumgil.core.Glyph
import com.cheoeumgil.core.Icon
import com.cheoeumgil.core.Hotspot
import com.cheoeumgil.core.RouteHistory
import com.cheoeumgil.core.SpeechQueue
import com.cheoeumgil.core.Spot
import com.cheoeumgil.core.StruggleDetector
import com.cheoeumgil.core.StruggleEvent
import com.cheoeumgil.core.StruggleKind
import com.cheoeumgil.core.Turn
import com.cheoeumgil.core.VoiceQuery
import com.cheoeumgil.core.Wire
import com.cheoeumgil.core.hotspots
import com.cheoeumgil.core.hotspotsAhead
import com.cheoeumgil.core.iconFor
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 처음길 prototype. ① briefs hard sections 1.5km ahead by voice from NaviHelper TBT and caution points.
 * ② 헤맴 지도: vehicle/navi signals -> struggle events (saved locally) -> hotspots -> briefed when ahead.
 */
class MainActivity : Activity() {

    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private lateinit var tts: TextToSpeech
    private lateinit var headerView: TextView
    private lateinit var distanceView: TextView
    private lateinit var titleView: TextView
    private lateinit var tipView: TextView
    private lateinit var iconView: ManeuverIconView
    private lateinit var upcomingView: TextView
    private lateinit var logView: TextView
    private var shown = IDLE
    private lateinit var captionView: TextView
    private var question: String? = null
    private var upcoming = listOf<String>()
    private val debug by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    private var engine = BriefingEngine()
    private val history = RouteHistory()
    private val historyFile by lazy { File(filesDir, "route_history.tsv") }
    private var destination = ""
    private val detector = StruggleDetector()
    private val eventsFile by lazy { File(filesDir, "struggle_events.csv") }
    private val events = mutableListOf<StruggleEvent>()
    private var localHotspots = listOf<Hotspot>()
    private var serverHotspots: List<Hotspot>? = null
    private val hotspotList get() = serverHotspots ?: localHotspots
    private val server = HotspotClient(BuildConfig.HOTSPOT_SERVER)
    private val uploadMarkFile by lazy { File(filesDir, "upload_mark") } // timeMs of the newest event already handled
    private val privateFile by lazy { File(filesDir, "private_spots.csv") } // trip starts/destinations, never uploaded
    private var lastHere: Spot? = null
    private var uploading = false // route-end signals arrive in pairs; one upload at a time
    private var lastHeading = 0.0
    private var stopped = true
    private var startPending = false // route started before the first location fix
    private val ui = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var lastSpeedLimit = -1
    private lateinit var stt: SpeechToText
    private var sttReady = false
    private var lastTurns = listOf<Turn>()
    private var lastHotspot: Pair<Hotspot, Double>? = null
    private var lastBriefing: String? = null

    private val sttResults = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (isFinal) ui.post { onQuestion(text) }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onStartedRecognition() = log("STT", "listening")
        override fun onEndedRecognition() = log("STT", "ended")
        override fun onError(error: SttError) = log("STT", "error: $error")
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }
    private val speech = SpeechQueue()

    private val ttsEvents = object : EventListener {
        override fun onStart() = log("TTS", "speaking")
        override fun onReady() {}
        override fun onDone() = log("TTS", "done").also { ui.post(::speakFinished) }
        override fun onError(error: ErrorType) = log("TTS", "error: $error").also { ui.post(::speakFinished) }
        override fun onStop() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            log("BRAKE", "pressed=$pressed")
            ui.post { detector.onBrake(pressed == true, now()) }
        }
        override fun onFailed(e: Exception) = log("BRAKE", "failed: $e")
    }
    private val gear = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) = log("GEAR", "$value")
        override fun onFailed(e: Exception) = log("GEAR", "failed: $e")
    }
    private val turnSignal = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            log("TURN", "$value")
            ui.post { record(detector.onTurnSignal(value != null && value != TurnSignalValue.OFF, now())) }
        }
        override fun onFailed(e: Exception) = log("TURN", "failed: $e")
    }
    private val cruise = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(value: CruiseControl?) {
            log("CRUISE", "$value")
            ui.post { record(detector.onCruise(value == CruiseControl.ACTIVATED, now())) }
        }
        override fun onFailed(e: Exception) = log("CRUISE", "failed: $e")
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            log("NAVI", "status=$status")
            // guidance ended: the navi reports FREE_DRIVE (onRouteEnded is not always sent)
            if (status == NavigationStatus.FREE_DRIVE) ui.post(::clearRoute)
        }
        override fun onRouteStarted(info: RouteStartInfo) {
            log("NAVI", "route started $info")
            ui.post { startRoute(info.name) }
        }
        override fun onRouteEnded() {
            ui.post(::clearRoute)
        }
        override fun onRouteCancelled() {
            ui.post(::clearRoute)
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            ui.post { remember(Spot(info.latitude, info.longitude)) }
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            log("NAVI", "arrived $info")
            ui.post { arrived() }
        }
        override fun onReRouteEnded() {
            log("REROUTE", "re-route ended")
            ui.post { record(detector.onReRoute(now())) }
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            ui.post { onLocation(Spot(info.latitude, info.longitude), info.heading) }
        }
        override fun onError(code: NaviErrorCode) = log("NAVI", "error=$code")
        override fun onTBTInfo(tbt: List<TBTInfo>) {
            ui.post { onTurns(tbt.map { Turn(it.type.name, it.distance, it.description) }) }
        }
        override fun onCautionPointInfo(points: List<CautionPoint>) {
            ui.post { engine.onCautions(points.map { Caution(it.id, it.type.name, it.remainDistance) }).forEach(::say) }
        }
        override fun onCurrentRoadSpeedLimit(limit: Int) {
            if (limit != lastSpeedLimit) log("SPEED_LIMIT", "$limit").also { lastSpeedLimit = limit }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        // ON_DEVICE: on-board engine, no server app registration needed
        tts = TextToSpeech(applicationContext, Mode.ON_DEVICE)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(ttsEvents)
                tts.setLocale(Locale.KOREA)
                log("TTS", "ready (Pleos on-device)")
                ui.post(::speakFinished) // speaks whatever queued up during start-up
            }
            override fun onFailure(error: ErrorType) = log("TTS", "init failed: $error")
        })

        // ON_DEVICE: on-board recognizer, no server app registration needed
        stt = SpeechToText(applicationContext, SttMode.ON_DEVICE)
        stt.initialize(object : SttInitListener {
            override fun onSuccess() {
                stt.addListener(sttResults)
                stt.setLocale(Locale.KOREA)
                sttReady = true
                log("STT", "ready (Pleos on-device)")
                ui.post { runSttFileTest(intent) }
            }
            override fun onFailure(error: SttError) = log("STT", "init failed: $error")
        })

        loadEvents()
        runCatching { if (historyFile.exists()) history.decode(historyFile.readText()) }
        detector.tripId = "drive-${now()}" // replaced by trip-… when a route starts

        vehicle = Vehicle(this)
        runCatching {
            vehicle.initialize()
            vehicle.getBrake().registerBrakePedalStatus(brake)
            vehicle.getGear().registerGearSelection(gear)
            vehicle.getTurnSignal().registerTurnSignalState(turnSignal)
            vehicle.getDrivingMode().registerCruiseControlState(cruise)
        }.onSuccess { log("VEHICLE", "listeners registered") }
            .onFailure { log("VEHICLE", "init failed: $it") }

        navi = NaviHelper(this)
        navi.initialize(
            {
                navi.addListener(naviListener)
                navi.getRouteStateInfo()
                navi.getTBTInfo()
                navi.getCurrentLocationInfo()
                log("NAVI", "initialized")
            },
            { log("NAVI", "init failed: $it") },
        )
    }

    override fun onResume() {
        super.onResume()
        // ponytail: NaviHelper only delivers while we're in front, so re-pull TBT on return; background delivery is an open question for Pleos
        runCatching { navi.getTBTInfo() }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getBrake().unregisterBrakePedalStatus(brake)
            vehicle.getGear().unregisterGearSelection(gear)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignal)
            vehicle.getDrivingMode().unregisterCruiseControlState(cruise)
            vehicle.release()
        }
        runCatching {
            navi.removeListener(naviListener)
            navi.release()
        }
        runCatching {
            stt.removeListener(sttResults)
            stt.release()
        }
        runCatching {
            tts.removeEventListener(ttsEvents)
            tts.release()
        }
        super.onDestroy()
    }

    /** Window bar / split changes resize us; rebuild the layout but keep engine, TTS and SDK state. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
    }

    /**
     * Portrait (one window, next to the navi): the next hard section only, large type.
     * Landscape (both windows): also the list of sections further ahead. Demo + log only in debug builds.
     */
    private fun buildUi() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fun label(size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        distanceView = label(56f, "#7FD4FF", bold = true)
        titleView = label(36f, "#FFFFFF", bold = true)
        tipView = label(26f, "#D6E2EE").apply { setPadding(0, 16, 0, 0) }
        upcomingView = label(24f, "#D6E2EE").apply { setPadding(48, 32, 48, 32) }
        // debug-only caption of what was asked/spoken: screen recordings carry no audio
        captionView = label(22f, "#FFE08A").apply { setPadding(0, 24, 0, 0) }
        logView = label(14f, "#8A9BAD").apply { setPadding(32, 16, 32, 32); text = lines.joinToString("\n") }

        val dp = resources.displayMetrics.density
        iconView = ManeuverIconView(this)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(distanceView); addView(titleView); addView(tipView)
        }
        // pictogram left of the text: the maneuver reads at a glance, the words confirm it
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(iconView, LinearLayout.LayoutParams((120 * dp).toInt(), (120 * dp).toInt()).apply { marginEnd = (24 * dp).toInt() })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#16324F"))
            headerView = label(20f, "#9FB3C8")
            addView(headerView)
            addView(row)
            // in-car touch target: at least 72dp tall
            addView(Button(context).apply {
                text = "물어보기"
                textSize = 26f
                minimumHeight = (72 * dp).toInt()
                setOnClickListener { listen() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 })
            if (debug) addView(captionView)
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card)
            addView(upcomingView)
        }
        val devTools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply { text = "데모 주행 (녹화된 판교 경로)"; setOnClickListener { startDemo() } })
            addView(Button(context).apply { text = "헤맴 지도 데모 (가상 기록)"; setOnClickListener { startHotspotDemo() } })
            addView(ScrollView(context).apply { addView(logView) })
        }
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1C2C"))
            val weight = if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(main, weight)
            if (debug) addView(devTools, LinearLayout.LayoutParams(weight))
        }
        setContentView(root)
        render()
    }

    private fun render() {
        headerView.text = if (engine.quiet) "처음길 · 익숙한 길이라 꼭 필요한 안내만" else "처음길 · 다음 어려운 구간"
        distanceView.text = shown.distance
        distanceView.visibility = if (shown.distance.isEmpty()) TextView.GONE else TextView.VISIBLE
        titleView.text = shown.title
        tipView.text = shown.tip
        iconView.icon = shown.icon
        upcomingView.text = if (upcoming.isEmpty()) "이어지는 어려운 구간이 없어요." else "이어지는 구간\n" + upcoming.joinToString("\n")
        val detail = if (stopped) TextView.VISIBLE else TextView.GONE
        tipView.visibility = detail
        upcomingView.visibility = detail
    }

    private fun onTurns(turns: List<Turn>) {
        if (turns.isEmpty()) return clearRoute()
        lastTurns = turns
        shown = engine.nextHard(turns)?.let { s ->
            val tip = BriefingEngine.action((s.next ?: s.turn).type).second
            Shown("${BriefingEngine.distance(s.aheadM)} 앞", s.title, tip, iconFor(s))
        } ?: Shown("", "1.5킬로미터 안에 어려운 구간이 없어요", "편하게 내비를 따라가세요.")
        upcoming = engine.hardSections(turns, 5_000.0).drop(1)
            .map { "${BriefingEngine.distance(it.aheadM)} · ${it.title}" }
        render()
        engine.summary(turns)?.let(::say)
        engine.onTurns(turns).forEach(::say)
    }

    /** New route: fresh briefing state, quiet mode if the driver has arrived here often enough. */
    private fun startRoute(name: String) {
        destination = name
        detector.tripId = "trip-${now()}"
        engine = BriefingEngine().apply { quiet = history.isFamiliar(name) }
        lastHere?.let { remember(it); fetchHotspots(it) } ?: run { startPending = true }
        log("NAVI", "destination=$name familiar=${engine.quiet}")
        render()
        if (engine.quiet) say("자주 가던 길이라 꼭 필요한 안내만 드릴게요.")
    }

    private fun clearRoute() {
        uploadPending()
        captionView.text = ""
        shown = IDLE
        upcoming = emptyList()
        lastTurns = emptyList()
        render()
    }

    /** A trip's start or destination: events near it stay on the device. */
    private fun remember(spot: Spot) {
        if (spot.lat == 0.0 && spot.lon == 0.0) return // navi reports 0,0 when there is no destination
        runCatching { privateFile.appendText("${spot.lat},${spot.lon}\n") }
    }

    /** Null when the file can't be read: the caller must then hold the upload (fail closed). */
    private fun privateSpots(): List<Spot>? = runCatching {
        if (!privateFile.exists()) emptyList()
        else privateFile.readLines().mapNotNull { l -> l.split(",").takeIf { it.size == 2 }?.let { Spot(it[0].toDouble(), it[1].toDouble()) } }
    }.getOrNull()

    /** Server hotspots for this area; on failure keep using the on-device aggregation. */
    private fun fetchHotspots(here: Spot) {
        if (!server.enabled) return
        Thread {
            val got = server.near(here)
            ui.post {
                serverHotspots = got
                log("SERVER", got?.let { "hotspots ${it.size} from server" } ?: "unreachable, using on-device (${localHotspots.size})")
                checkHotspots()
            }
        }.start()
    }

    /**
     * Sends events newer than the last handled one, minus those near private spots. A timestamp mark
     * (not a list position) stays correct even if the local store is reset or trimmed.
     * Only route trips (start recorded) go out; free drives without a destination stay in the car.
     */
    private fun uploadPending() {
        if (!server.enabled || uploading) return
        val mark = runCatching { uploadMarkFile.readText().trim().toLong() }.getOrDefault(0L)
        val pending = events.filter { it.timeMs > mark }
        if (pending.isEmpty()) return
        val spots = privateSpots()
        if (spots.isNullOrEmpty()) return log("SERVER", "start/destination unknown, upload held")
        val newMark = pending.maxOf { it.timeMs }
        val batch = Wire.uploadable(pending.filter { it.tripId.startsWith("trip-") }, spots)
        uploading = true
        Thread {
            val ok = batch.isEmpty() || server.upload(batch)
            ui.post {
                uploading = false
                if (ok) runCatching { uploadMarkFile.writeText("$newMark") }
                log("SERVER", if (ok) "uploaded ${batch.size} of ${pending.size} events" else "upload failed, will retry")
            }
        }.start()
    }

    private fun arrived() {
        history.arrived(destination)
        runCatching { historyFile.writeText(history.encode()) }
        uploadPending()
        log("NAVI", "arrival saved for $destination")
    }

    private fun listen() {
        if (!sttReady) return say("음성 인식을 준비하고 있어요.", briefing = false)
        headerView.text = "처음길 · 듣고 있어요"
        stt.request()
    }

    /** Answers a spoken question from the current route state (see [VoiceQuery]). */
    private fun onQuestion(text: String) {
        val ask = VoiceQuery.classify(text)
        val sections = engine.hardSections(lastTurns, 5_000.0)
        val answer = VoiceQuery.answer(ask, sections.firstOrNull(), sections.size, lastHotspot, lastBriefing)
        log("ASK", "\"$text\" -> $ask")
        question = text
        answer.setQuiet?.let { engine.quiet = it }
        render()
        say(answer.text, briefing = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runSttFileTest(intent)
    }

    /**
     * Debug-only: `am start ... --ez stt_file_test true` feeds files/query.pcm (16 kHz, 16-bit mono)
     * to the recognizer instead of the microphone, so voice questions can be tested on the emulator.
     */
    private fun runSttFileTest(intent: Intent?) {
        if (!debug || !sttReady || intent?.getBooleanExtra("stt_file_test", false) != true) return
        intent.removeExtra("stt_file_test")
        val pcm = File(filesDir, "query.pcm").takeIf { it.exists() }?.readBytes() ?: return log("STT", "no query.pcm")
        log("STT", "file test: ${pcm.size} bytes")
        Thread {
            pcm.asList().chunked(3_200).forEach { stt.sendAudio(it.toByteArray()); Thread.sleep(100) }
            stt.completeAudioSend()
        }.start()
    }

    private fun now() = System.currentTimeMillis()

    private fun onLocation(here: Spot, headingDeg: Double) {
        lastHere = here
        lastHeading = headingDeg
        if (startPending) {
            startPending = false
            remember(here)
            fetchHotspots(here)
        }
        record(detector.onLocation(here, now()))
        val s = detector.speedKmh < STOPPED_KMH
        if (s != stopped) { stopped = s; render() }
        checkHotspots()
    }

    private fun checkHotspots() {
        val here = lastHere ?: return
        val ahead = hotspotsAhead(here, lastHeading, hotspotList)
        lastHotspot = ahead.firstOrNull()
        engine.onHotspots(ahead).forEach(::say)
    }

    /** Appends one event to the local store as `kind,lat,lon,timeMs,tripId` and refreshes the hotspots. */
    private fun record(e: StruggleEvent?) {
        e ?: return
        events += e
        localHotspots = hotspots(events)
        runCatching { eventsFile.appendText("${e.kind},${e.spot.lat},${e.spot.lon},${e.timeMs},${e.tripId}\n") }
        log("STRUGGLE", "${e.kind} at ${e.spot} (hotspots=${hotspotList.size})")
    }

    private fun loadEvents() {
        runCatching {
            if (!eventsFile.exists()) return@runCatching
            eventsFile.readLines().mapNotNullTo(events) { line ->
                val f = line.split(",")
                runCatching { StruggleEvent(StruggleKind.valueOf(f[0]), Spot(f[1].toDouble(), f[2].toDouble()), f[3].toLong(), f[4]) }.getOrNull()
            }
            localHotspots = hotspots(events)
        }
        log("STRUGGLE", "loaded ${events.size} events, ${hotspotList.size} hotspots")
    }

    private fun say(text: String, briefing: Boolean = true) {
        log("SAY", text)
        captionView.text = (question?.let { "🎤 \"$it\"\n" } ?: "") + "🔊 $text"
        question = null
        if (briefing) lastBriefing = text
        speech.offer(text)?.let(tts::speak)
    }

    private fun speakFinished() {
        speech.done()?.let(tts::speak)
    }

    /** Replays the TBT list recorded from the emulator's 판교테크노밸리 route, ~60 km/h at 2x speed. */
    private fun startDemo() {
        ui.removeCallbacksAndMessages(null)
        engine = BriefingEngine()
        var route = listOf(
            Turn("TURN_LEFT", 80.0, "기업지원허브 방면"),
            Turn("TURN_RIGHT", 380.0, "판교IC 방면"),
            Turn("TURN_AVOID_UNDER_R", 730.0, "판교IC 방면"),
            Turn("TURN_RIGHT", 820.0, "경기창조경제혁신센터 방면"),
            Turn("TURN_RIGHT", 200.0, "이름 없는 도로"),
            Turn("TURN_RIGHT", 50.0, "이름 없는 도로"),
            Turn("TURN_ARRIVE", 10.0, "도착"),
        )
        log("DEMO", "start")
        val step = object : Runnable {
            override fun run() {
                var move = 16.5 // metres per 0.5s tick
                while (route.isNotEmpty() && route[0].gapM <= move) {
                    move -= route[0].gapM
                    route = route.drop(1)
                }
                if (route.isEmpty()) {
                    shown = Shown("", "도착했어요", "")
                    upcoming = emptyList()
                    render()
                    log("DEMO", "arrived")
                    return
                }
                route = listOf(route[0].copy(gapM = route[0].gapM - move)) + route.drop(1)
                onTurns(route)
                ui.postDelayed(this, 500)
            }
        }
        ui.post(step)
    }

    /**
     * Shows the 헤맴 지도 pipeline end to end: three synthetic trips that re-routed at one spot (demo-only data,
     * never saved) become a hotspot, then the car drives north toward it and gets briefed.
     */
    private fun startHotspotDemo() {
        ui.removeCallbacksAndMessages(null)
        val start = Spot(37.4127, 127.0947)
        fun north(m: Double) = start.copy(lat = start.lat + m / 111_320.0)
        val demoSpots = hotspots((1..3).map { StruggleEvent(StruggleKind.REROUTE, north(1200.0 + it * 5), 0, "demo-$it") })
        val demoEngine = BriefingEngine()
        log("DEMO", "가상 기록 3건 -> 헤맴 구간 ${demoSpots.size}곳 (북쪽 1.2km)")
        var driven = 0.0
        val step = object : Runnable {
            override fun run() {
                driven += 16.5
                val ahead = hotspotsAhead(north(driven), 0.0, demoSpots)
                shown = ahead.firstOrNull()?.let { (h, d) ->
                    Shown("${BriefingEngine.distance(d)} 앞", "헤맴 구간", "서로 다른 주행 ${h.trips}번에서 ${h.main.reason} 곳이에요. 미리 차로와 속도를 준비하세요.", Icon(Glyph.WARNING, false))
                } ?: Shown("", "헤맴 지도 데모 주행 중", "")
                render()
                demoEngine.onHotspots(ahead).forEach(::say)
                if (driven < 1300) ui.postDelayed(this, 500) else log("DEMO", "hotspot demo done")
            }
        }
        ui.post(step)
    }

    private fun log(tag: String, msg: String) {
        Log.i("Cheoeumgil", "$tag $msg")
        runOnUiThread {
            lines.addFirst("${clock.format(Date())}  [$tag] $msg")
            while (lines.size > 200) lines.removeLast()
            if (::logView.isInitialized) logView.text = lines.joinToString("\n")
        }
    }
}

/** What the card shows: distance ahead (may be empty), what it is, what to do. */
private data class Shown(val distance: String, val title: String, val tip: String, val icon: Icon? = null)

private const val STOPPED_KMH = 5.0

private val IDLE = Shown("", "경로를 설정하면", "어려운 구간을 1.5킬로미터 전에 미리 알려드릴게요.")
