package ai.gatita.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ai.pleos.playground.vehicle.constant.area.DoorValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 같이타 – 탑승자 맞춤 음성 동승자.
 * 출발 시 "오늘 누구와 함께하시나요?"로 탑승자를 정하고(음성 또는 좌석 누르기),
 * 기어(주행/정차)·도어·창문·내비 남은 시간에 맞춰 동작합니다. 화면 디자인 원본은 design/mockups.html.
 */
class MainActivity : Activity(), VehicleBridge.Events, NaviBridge.Events {

    enum class Mode { ASK, KID, PET, SOLO }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var vehicle: VehicleBridge
    private lateinit var navi: NaviBridge
    private lateinit var voice: Voice
    private val story = Story()

    // 상태
    private var mode = Mode.ASK
    // 운전석은 항상 운전자(어른). 나머지 좌석만 묻거나 눌러서 정합니다
    private val riders = Seat.values().associateWith { if (it == Seat.D) Rider.ADULT else Rider.NONE }.toMutableMap()
    private var parked = true
    private var gearLabel = "P"
    private var remainSec = -1
    private var naviLive = false
    private var lastStoriesLeft = -1
    private var chapter: Story.Chapter? = null
    private var chapterStartRemain = 0
    private var riverDone = false
    private var rearOpenedSincePark = false
    private var driveSeconds = 0
    private var soloAlerted = false
    private var restStop: RestStops.Stop? = null
    private var restKm: Double? = null
    private var restAdded = false
    private var awaitingChoice = false
    private var storyStarted = false
    private var alertMsg: String? = null

    // 고정 뷰
    private lateinit var stage: FrameLayout
    private lateinit var ambientLine: AmbientLine
    private lateinit var modeDot: View
    private lateinit var modeText: TextView
    private lateinit var etaBox: LinearLayout
    private lateinit var etaLabel: TextView
    private lateinit var etaText: TextView
    private lateinit var clockText: TextView
    private val gearTexts = mutableMapOf<String, TextView>()
    private val railIcons = mutableMapOf<String, IconView>()

    // 지금 화면에서 바뀌는 뷰 (화면이 바뀌면 다시 채워짐)
    private var screen = ""
    private var wave: WaveView? = null
    private var seatMap: SeatMapView? = null
    private var ring: RingView? = null
    private var ringTitle: TextView? = null
    private var elapsed: TextView? = null
    private var bubbleText: TextView? = null
    /** 탑승자 묻기 화면: 앱이 이해한 탑승자를 정리해 보여 주는 줄 */
    private var heardText: TextView? = null
    private var driveText: TextView? = null
    private var map: NavMapView? = null
    private var restCard: View? = null
    private var restName: TextView? = null
    private var restInfo: TextView? = null
    private var restButtons: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        UI_SCALE = min(dm.widthPixels / 2560f, dm.heightPixels / 1440f)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        voice = Voice(this) { log(it) }.also { it.start() }
        // shortcut: STT RMS 범위를 0~10으로 가정. 실기에서 값 범위를 보고 나누는 수를 조정
        voice.onRms = { rms -> wave?.level = (rms / 10f).coerceIn(0f, 1f) }
        vehicle = VehicleBridge(this, this).also { it.start() }
        navi = NaviBridge(this, this).also { it.start() }

        ui.postDelayed({ ask() }, 1500)
        ui.postDelayed(tick, 1000)
        // 5초 안에 내비 경로 정보가 없으면 시연용 경로(13분 20초)를 시뮬레이션합니다.
        ui.postDelayed({ if (!naviLive && remainSec < 0) { remainSec = 800; refresh() } }, 5000)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        voice.release(); vehicle.release(); navi.release()
        super.onDestroy()
    }

    // ───────── 흐름 ─────────

    private fun ask() {
        mode = Mode.ASK
        ambient(Pal.KID)
        render()
        voice.say("오늘 누구와 함께하시나요?")
        ui.postDelayed({ voice.listen { heard -> ui.post { onHeardRiders(heard) } } }, 2200)
    }

    private fun onHeardRiders(heard: String) {
        if (mode != Mode.ASK) return
        val found = parseRiders(heard)
        if (found == null) {
            showHeard(null)
            voice.say("자리를 눌러서 알려 주셔도 돼요.")
            return
        }
        riders.putAll(found)
        seatMap?.invalidate()
        showHeard(found)
        voice.say("${summary()}, 같이 출발할게요.")
        ui.postDelayed({ startTrip() }, 3500)   // 정리된 탑승자 문구를 읽을 시간
    }

    /** 좌석을 누를 때마다 ? → 어른 → 아이 → 어르신 → 반려동물 */
    private fun tapSeat(seat: Seat) {
        if (mode != Mode.ASK || seat == Seat.D) return
        val order = Rider.values()
        riders[seat] = order[(riders.getValue(seat).ordinal + 1) % order.size]
        seatMap?.invalidate()
        showHeard(riders)
        // 이미 주행 중이면 4초 동안 더 누르지 않을 때 시작 (정차 중이면 출발할 때 시작)
        ui.removeCallbacks(startFromTaps)
        if (!parked) ui.postDelayed(startFromTaps, 4000)
    }

    private val startFromTaps = Runnable { startTrip() }

    private fun startTrip() {
        if (mode != Mode.ASK) return
        ui.removeCallbacks(startFromTaps)
        if (riders[Seat.D] == Rider.NONE) riders[Seat.D] = Rider.ADULT
        val kids = count(Rider.KID)
        val pet = count(Rider.PET) > 0
        mode = when { kids > 0 -> Mode.KID; pet -> Mode.PET; else -> Mode.SOLO }
        // 창문을 여는 일은 없고, 반려동물이 타면 뒷창이 환기 높이보다 더 열리지 않게 지키기만 합니다
        vehicle.guardRearWindows = pet
        when (mode) {
            Mode.KID -> {
                val r = if (remainSec > 0) remainSec else 800
                story.plan(r); lastStoriesLeft = story.storiesLeft(r)
                ambient(Pal.KID)
                voice.say("좋아요. 도착할 때까지 이야기 ${lastStoriesLeft}개를 준비했어요." + if (pet) " 뒷창은 환기 높이 이상 열리지 않게 지킬게요." else "")
            }
            Mode.PET -> {
                ambient(Pal.PET)
                voice.say("반려동물이 함께 타서 뒷창이 환기 높이 이상 열리지 않게 지킬게요.")
            }
            else -> {
                ambient(Pal.SOLO)
                // 아이·반려동물이 없을 때. 어른·어르신이 함께면 "혼자"라고 하지 않습니다
                if (summary() == "혼자") voice.say("혼자 운전하시는군요. 오래 운전하시면 쉬어 갈 곳을 알려 드릴게요.")
                else voice.say("오래 운전하시면 쉬어 갈 곳을 알려 드릴게요.")
            }
        }
        render()
    }

    /** 1초마다: 남은 시간 갱신(시뮬레이션), 이야기 진행, 졸음 코치, 화면 숫자 갱신 */
    private val tick = object : Runnable {
        override fun run() {
            if (!naviLive && remainSec > 0 && !parked) remainSec = (remainSec - SIM_SPEED).coerceAtLeast(0)
            if (!parked) driveSeconds++
            // 대답도 좌석 선택도 없이 출발하면 잠시 뒤 혼자 운전으로 시작
            if (mode == Mode.ASK && !parked && driveSeconds >= ASK_TIMEOUT_DRIVE_SEC) startTrip()
            // 휴게소 방향 판단에 쓰도록 주행 중에는 10초마다 현재 위치(진행 방향 포함)를 새로 받음
            if (!parked && driveSeconds % 10 == 0) navi.requestLocation()
            when (mode) {
                Mode.KID -> kidTick()
                Mode.SOLO -> soloTick()
                else -> {}
            }
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun kidTick() {
        if (remainSec < 0 || parked || awaitingChoice) return
        val left = story.storiesLeft(remainSec)
        if (remainSec == 0 || left == 0) return
        if (!storyStarted) {
            // 출발(D)하는 순간 1화를 바로 시작
            storyStarted = true
            play(story.next(false))
            lastStoriesLeft = left
        } else if (left != lastStoriesLeft) {
            val bonus = left > lastStoriesLeft
            lastStoriesLeft = left
            if (!bonus && story.needsChoice) { askChoice(); return }
            play(if (left <= 1) story.ending else story.next(bonus))
        }
    }

    private fun play(ch: Story.Chapter) {
        chapter = ch
        chapterStartRemain = remainSec.coerceAtLeast(0)
        voice.say(ch.text)
        refresh()
    }

    private fun askChoice() {
        awaitingChoice = true
        ambient(Pal.CHOICE)
        voice.say(story.choiceQuestion)
        render()
        ui.postDelayed({ voice.listen { h -> ui.post { if (h.contains("숲")) chooseBranch(true) else if (h.contains("동굴")) chooseBranch(false) } } }, 1500)
        // 8초 안에 대답이 없으면 숲으로 진행 (주행 중 화면 조작 없이도 이야기가 이어지도록)
        ui.postDelayed({ if (awaitingChoice) { voice.say("대답이 없네? 그럼 숲으로 가 볼까?"); ui.postDelayed({ chooseBranch(true) }, 2500) } }, 8000)
    }

    /** 말로 고르거나 카드를 눌러서 고름 */
    private fun chooseBranch(forest: Boolean) {
        if (!awaitingChoice) return
        awaitingChoice = false
        story.choose(forest)
        ambient(Pal.KID)
        voice.say(if (forest) "좋아, 숲으로 가자!" else "좋아, 동굴로 가자!")
        render()
        ui.postDelayed({ play(story.next(false)) }, 2500)
    }

    private fun soloTick() {
        if (parked || soloAlerted || driveSeconds < SOLO_ALERT_SEC) return
        soloAlerted = true
        val (stop, km) = navi.nearestStop()
        restStop = stop; restKm = km; restAdded = false
        ambient(Pal.REST)
        voice.say("연속으로 오래 운전하셨어요. ${stop.name.substringBefore("(")}에서 15분 쉬어 갈까요?")
        refresh()
        ui.postDelayed({
            voice.listen { h ->
                ui.post {
                    if (listOf("응", "좋아", "그래", "네", "추가").any { h.contains(it) }) acceptRest()
                    else if (listOf("아니", "괜찮").any { h.contains(it) }) declineRest()
                }
            }
        }, 4500)
    }

    private fun acceptRest() {
        if (restStop == null || restAdded) return
        val stop = navi.addRestStop()
        restStop = stop; restAdded = true
        voice.say("${stop.name.substringBefore("(")}를 경유지로 추가했어요. 조금만 더 힘내요!")
        refresh()
    }

    private fun declineRest() {
        if (restStop == null || restAdded) return
        restStop = null
        ambient(Pal.SOLO)
        voice.say("알겠어요. 졸리면 언제든 말해 주세요.")
        refresh()
    }

    private fun clearAlert() {
        if (alertMsg == null) return
        alertMsg = null
        ambient(modeColor())
        render()
    }

    // ───────── 차량 이벤트 ─────────

    override fun onGear(parked: Boolean, label: String) = ui.post {
        val wasDriving = !this.parked
        this.parked = parked; gearLabel = label
        if (parked && wasDriving) { rearOpenedSincePark = false; driveSeconds = 0; soloAlerted = false }
        // 좌석을 눌러 탑승자를 정해 둔 채 출발하면 그대로 시작
        if (!parked && !wasDriving && mode == Mode.ASK && riders.any { (s, r) -> s != Seat.D && r != Rider.NONE }) startTrip()
        refresh()
    }.let { }

    override fun onDoor(door: DoorValue, open: Boolean) = ui.post {
        if (open && (door == DoorValue.ROW_2_LEFT || door == DoorValue.ROW_2_RIGHT)) { rearOpenedSincePark = true; clearAlert() }
        // 하차 리마인더: 아이/반려동물이 탔고 + 정차 + 운전석 문 열림 + 뒷문은 아직 안 열림
        val kids = count(Rider.KID)
        val pet = count(Rider.PET) > 0
        if (open && door == DoorValue.ROW_1_LEFT && parked && !rearOpenedSincePark && mode != Mode.ASK && (kids > 0 || pet)) {
            alertMsg = if (kids > 0) "뒷좌석에 아이 ${kids}명이\n아직 있어요" else "뒷좌석에 반려동물이\n아직 있어요"
            ambient(Pal.ALERT)
            voice.say("잠깐만요! 뒷좌석에 ${if (kids > 0) "아이가" else "반려동물이"} 있어요!", urgent = true)
            render()
        }
    }.let { }

    override fun onRearWindow(position: Int) {}

    private var lastClampSay = 0L

    override fun onRearWindowClamped() = ui.post {
        // 창은 열린 것마다 되돌리지만, 안내는 한 번만 (두 창을 함께 열거나 슬라이더로 여러 번 바뀌어도)
        val now = System.currentTimeMillis()
        if (now - lastClampSay > CLAMP_SAY_GAP_MS) {
            lastClampSay = now
            voice.say("창문이 너무 많이 열렸어요. 환기만 되게 다시 맞출게요.")
        }
    }.let { }

    override fun onRemainSeconds(sec: Int, destination: String) = ui.post {
        naviLive = true; remainSec = sec
        refresh()
    }.let { }

    override fun onAddress(address: String) = ui.post {
        if (mode == Mode.KID && !riverDone && (address.contains("강") || address.contains("대교") || address.contains("교량"))) {
            riverDone = true; ambient(0xFF1FB6FF.toInt()); voice.say(story.river)
        }
    }.let { }

    override fun onArrived() = ui.post { remainSec = 0; refresh() }.let { }

    override fun onLog(msg: String) = log(msg)

    // ───────── 화면 ─────────

    private fun render() {
        val a = alertMsg
        when {
            a != null -> show("alert", "home", Pal.ALERT) { alertScreen(a) }
            mode == Mode.ASK -> show("ask", "users", Pal.KID) { askScreen() }
            awaitingChoice -> show("choice", "book", Pal.CHOICE) { choiceScreen() }
            mode == Mode.KID -> show("kid", "book", Pal.KID) { kidScreen() }
            mode == Mode.PET -> show("pet", "window", Pal.PET) { petScreen() }
            else -> show("solo", "cup", Pal.SOLO) { soloScreen() }
        }
        refresh()
    }

    /** 화면이 바뀔 때만 새로 만들고, 같은 화면이면 refresh()로 숫자만 갱신 */
    private fun show(id: String, railOn: String, color: Int, build: () -> View) {
        if (screen == id) return
        screen = id
        wave = null; seatMap = null; ring = null; ringTitle = null; elapsed = null; bubbleText = null; heardText = null
        driveText = null; map = null; restCard = null; restName = null; restInfo = null; restButtons = null
        stage.removeAllViews()
        stage.addView(build(), FrameLayout.LayoutParams(-1, -1))
        railIcons.forEach { (name, v) -> v.color = if (name == railOn) color else Pal.MUTE; v.glow = name == railOn; v.invalidate() }
    }

    private fun refresh() {
        // 상단 줄
        val active = mode != Mode.ASK
        modeDot.visibility = if (active) View.VISIBLE else View.GONE
        modeText.visibility = modeDot.visibility
        modeText.text = summary()
        (modeDot.background as GradientDrawable).setColor(modeColor())
        etaBox.visibility = if (active && remainSec >= 0) View.VISIBLE else View.GONE
        etaLabel.text = if (naviLive) "도착까지" else "시뮬레이션"
        etaText.text = clock(remainSec)
        gearTexts.forEach { (g, v) ->
            val on = g == gearLabel
            v.setTextColor(if (on) Color.WHITE else Pal.MUTE); v.typeface = font(if (on) 700 else 300)
        }
        clockText.text = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

        // 이야기: 링 = 이번 이야기 진행 정도, 아래 = 흐른 시간
        val ch = chapter
        val gone = if (ch != null) (chapterStartRemain - remainSec).coerceAtLeast(0) else 0
        ring?.progress = if (ch != null) gone / story.slot.toFloat() else 0f
        ringTitle?.text = ch?.title?.replace(" · ", "\n") ?: "이야기 ${lastStoriesLeft.coerceAtLeast(0)}개\n준비 완료"
        elapsed?.text = "%02d:%02d".format(gone / 60, gone % 60)
        bubbleText?.text = ch?.text ?: "출발하면 이야기를 시작해요"

        // 혼자: 운전 시간 + 휴게소 카드
        driveText?.text = "%02d:%02d".format(driveSeconds / 3600, driveSeconds % 3600 / 60)
        val stop = restStop
        map?.showPin = stop != null
        restCard?.visibility = if (stop != null) View.VISIBLE else View.GONE
        if (stop != null) {
            restName?.text = stop.name.substringBefore("(")
            val km = restKm
            // shortcut: 시속 80km로 어림한 시간. NaviHelper가 경유지 도착 예상 시간을 주면 교체
            restInfo?.text = when {
                restAdded -> "경유지로 추가했어요"
                km != null -> "${km.roundToInt()}km · ${(km / 80 * 60).roundToInt().coerceAtLeast(1)}분 뒤"
                else -> "가까운 휴게소"
            }
            restButtons?.visibility = if (restAdded) View.GONE else View.VISIBLE
        }
    }

    private fun askScreen(): View = col().apply {
        addView(h1("오늘 누구와 함께하시나요?"))
        val w = WaveView(context, Pal.KID); wave = w
        addView(w, lp(1340, 170, top = 44))
        // 들은 말 그대로가 아니라, 앱이 이해한 탑승자를 정리해 보여 줍니다
        val heard = text(HEARD_HINT, 40, Pal.MUTE, 400).apply { gravity = Gravity.CENTER }
        heardText = heard
        addView(heard, lp(-2, -2, top = 30))
        val m = SeatMapView(context, riders) { tapSeat(it) }; seatMap = m
        addView(m, lp(1080, 620, top = 20))
    }

    /** 이해한 탑승자를 "아이 2명 · 어르신 1명 · 반려동물"처럼 정리해 표시. null이면 못 알아들음 */
    private fun showHeard(found: Map<Seat, Rider>?) {
        val t = heardText ?: return
        if (found == null) {
            t.text = "잘 못 알아들었어요 · 자리를 눌러 알려 주세요"
            t.setTextColor(Pal.ALERT)
            return
        }
        val n = { r: Rider -> found.values.count { it == r } }
        val parts = mutableListOf<String>()
        if (n(Rider.KID) > 0) parts += "아이 ${n(Rider.KID)}명"
        if (n(Rider.ELDER) > 0) parts += "어르신 ${n(Rider.ELDER)}명"
        if (n(Rider.ADULT) - 1 > 0) parts += "어른 ${n(Rider.ADULT) - 1}명"   // 운전자 제외
        if (n(Rider.PET) > 0) parts += "반려동물 ${n(Rider.PET)}마리"
        val body = parts.joinToString(" · ")
        // 마지막 글자 받침이 있으면 "과", 없으면 "와" (명→과, 마리→와)
        val last = body.lastOrNull() ?: '가'
        val josa = if (last in '가'..'힣' && (last - '가') % 28 != 0) "과" else "와"
        t.text = if (parts.isEmpty()) "✓ 혼자 운전" else "✓ $body$josa 함께"
        t.setTextColor(Color.WHITE)
    }

    private fun kidScreen(): View = col().apply {
        val bubble = row().apply {
            setPadding(16.ipx(), 16.ipx(), 40.ipx(), 16.ipx())
            addView(FigView(context, Rider.KID), lp(70, 70))
            val t = text("", 42, Color.WHITE, 500).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = 1500.ipx() }
            bubbleText = t
            addView(t, lp(-2, -2, start = 20))
        }
        addView(outline(bubble, Pal.KID, true, 999))
        val box = FrameLayout(context)
        val r = RingView(context, Pal.KID); ring = r
        box.addView(r, FrameLayout.LayoutParams(-1, -1))
        val title = h1("", 92); ringTitle = title
        box.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        addView(box, lp(640, 640, top = 30))
        val e = text("00:00", 52, Pal.DIM, 200).apply { letterSpacing = .04f }
        elapsed = e
        addView(e, lp(-2, -2, top = 24))
    }

    private fun choiceScreen(): View = col().apply {
        addView(h1(story.choiceQuestion))
        val w = WaveView(context, Pal.CHOICE); wave = w
        addView(w, lp(1340, 170, top = 44))
        val cards = row()
        listOf(Triple(false, "cave", "동굴"), Triple(true, "forest", "숲")).forEachIndexed { i, (forest, icon, label) ->
            val card = col().apply {
                addView(IconView(context, icon, Pal.CHOICE, glow = true), lp(200, 200))
                addView(text(label, 60, Color.WHITE, 700), lp(-2, -2, top = 24))
                setOnClickListener { chooseBranch(forest) }
            }
            cards.addView(outline(card, Pal.CHOICE, true, 44), lp(560, 440, start = if (i == 0) 0 else 44))
        }
        addView(cards, lp(-2, -2, top = 70))
    }

    private fun petScreen(): View = col().apply {
        addView(WindowView(context), lp(1000, 460))
        val s = SpannableString("뒷창은 환기만 되게 열려요").apply { setSpan(ForegroundColorSpan(Pal.PET), 4, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        addView(h1(s), lp(-2, -2, top = 60))
    }

    private fun soloScreen(): View = row().apply {
        setPadding(120.ipx(), 0, 80.ipx(), 0)
        val left = col().apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(SeatFigureView(context, Rider.ADULT), lp(340, 340))
            // 운전 시간: 굵은 숫자 + 은은한 네온
            val t = text("00:00", 170, Pal.NEON, 800).apply { setShadowLayer(26.px(), 0f, 0f, Pal.REST); letterSpacing = .02f }
            driveText = t
            addView(t, lp(-2, -2, top = 10))
            addView(h1("운전 중"), lp(-2, -2))
        }
        addView(left, lp(-2, -1))

        val mapBox = FrameLayout(context).apply { clipToOutline = true }
        outline(mapBox, Pal.REST, true, 56, Pal.CARD)
        val m = NavMapView(context); map = m
        mapBox.addView(m, FrameLayout.LayoutParams(-1, -1))
        val card = col().apply {
            gravity = Gravity.START
            setPadding(48.ipx(), 40.ipx(), 48.ipx(), 40.ipx())
            addView(row().apply {
                addView(IconView(context, "cup", Pal.REST, glow = true), lp(72, 72))
                val n = text("", 58, Color.WHITE, 700); restName = n
                addView(n, lp(-2, -2, start = 22))
            })
            val info = text("", 52, Color.WHITE, 200); restInfo = info
            addView(info, lp(-2, -2, top = 14))
            val btns = row().apply {
                addView(button("경유지 추가", Pal.REST, true, "plus") { acceptRest() })
                addView(button("괜찮아요", Pal.REST, false) { declineRest() }, lp(-2, -2, start = 24))
            }
            restButtons = btns
            addView(btns, lp(-2, -2, top = 34))
        }
        outline(card, Pal.REST, true, 44, Pal.alpha(Pal.CARD, .92f))
        restCard = card
        mapBox.addView(card, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = 56.ipx(); topMargin = 56.ipx() })
        addView(mapBox, LinearLayout.LayoutParams(0, 1060.ipx(), 1f).apply { marginStart = 90.ipx() })
    }

    private fun alertScreen(msg: String): View = row().apply {
        gravity = Gravity.CENTER
        addView(CarTopView(context, count(Rider.KID), count(Rider.PET) > 0), lp(420, 730))
        val texts = col().apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(text("잠깐만요!", 150, Pal.ALERT, 700).apply { setShadowLayer(40.px(), 0f, 0f, Pal.alpha(Pal.ALERT, .5f)) })
            addView(h1(msg, 96).apply { gravity = Gravity.START }, lp(-2, -2, top = 10))
        }
        addView(texts, lp(-2, -2, start = 150))
        setOnClickListener { clearAlert() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.BG2, Pal.BG, Pal.BG))
        }
        // 상단 줄: 로고 | 탑승자 · 남은 시간 | P R N D | 시계
        val bar = row().apply {
            setPadding(60.ipx(), 0, 90.ipx(), 0)
            addView(LogoView(context), lp(70, 54))
            addView(text("같이타", 42, Color.WHITE, 700), lp(-2, -2, start = 18))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            modeDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.KID) } }
            addView(modeDot, lp(14, 14))
            modeText = text("", 30, Pal.DIM, 500)
            addView(modeText, lp(-2, -2, start = 14))
            etaBox = row().apply {
                addView(divider(), lp(2, 60, start = 40))
                etaLabel = text("도착까지", 30, Pal.DIM, 500)
                addView(etaLabel, lp(-2, -2, start = 40))
                etaText = text("", 58, Color.WHITE, 200)
                addView(etaText, lp(-2, -2, start = 14))
            }
            addView(etaBox)
            addView(divider(), lp(2, 60, start = 40))
            listOf("P", "R", "N", "D").forEach { g ->
                val t = text(g, 40, Pal.MUTE, 300); gearTexts[g] = t
                addView(t, lp(58, -2, start = 6))
            }
            addView(divider(), lp(2, 60, start = 40))
            clockText = text("", 58, Color.WHITE, 200)
            addView(clockText, lp(-2, -2, start = 40))
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, 150.ipx()))
        root.addView(divider(), FrameLayout.LayoutParams(-1, 2.ipx().coerceAtLeast(1)).apply { topMargin = 150.ipx() })
        // 왼쪽 아이콘 줄 (지금 화면에 해당하는 아이콘만 빛남)
        val rail = col().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 70.ipx(), 0, 0)
            listOf("home", "users", "book", "window", "cup", "light").forEachIndexed { i, n ->
                val icon = IconView(context, n, Pal.MUTE); railIcons[n] = icon
                addView(icon, lp(58, 58, top = if (i == 0) 0 else 58))
            }
        }
        root.addView(rail, FrameLayout.LayoutParams(190.ipx(), -1).apply { topMargin = 150.ipx() })
        root.addView(divider(), FrameLayout.LayoutParams(2.ipx().coerceAtLeast(1), -1).apply { topMargin = 150.ipx(); leftMargin = 190.ipx() })
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 150.ipx(); leftMargin = 190.ipx() })
        ambientLine = AmbientLine(this)
        root.addView(ambientLine, FrameLayout.LayoutParams(-1, 40.ipx(), Gravity.BOTTOM))
        setContentView(root)
        render()
    }

    // ───────── 작은 도우미 ─────────

    private fun ambient(rgb: Int) {
        vehicle.setAmbient(rgb)
        ambientLine.color = rgb
    }

    private fun count(r: Rider) = riders.values.count { it == r }

    /** 상단 줄과 음성 안내에 쓰는 탑승자 요약 (운전자 제외) */
    private fun summary(): String {
        val parts = mutableListOf<String>()
        val kids = count(Rider.KID)
        if (kids > 0) parts += if (kids > 1) "아이 $kids" else "아이"
        if (count(Rider.ELDER) > 0) parts += "어르신"
        if (count(Rider.PET) > 0) parts += "반려동물"
        val adults = count(Rider.ADULT) - 1
        if (adults > 0) parts += "어른 $adults"
        return if (parts.isEmpty()) "혼자" else parts.joinToString(" · ")
    }

    private fun modeColor() = when (mode) { Mode.KID -> Pal.KID; Mode.PET -> Pal.PET; else -> Pal.SOLO }

    private fun clock(sec: Int) = when {
        sec < 0 -> ""
        sec >= 3600 -> "%d:%02d".format(sec / 3600, sec % 3600 / 60)
        else -> "%02d:%02d".format(sec / 60, sec % 60)
    }

    private fun log(msg: String) { Log.d(TAG, msg) }

    private fun font(weight: Int): Typeface = Typeface.create(Typeface.DEFAULT, weight, false)

    private fun text(t: CharSequence, size: Int, color: Int = Color.WHITE, weight: Int = 400) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_PX, size.px()); setTextColor(color); typeface = font(weight)
        gravity = Gravity.CENTER; includeFontPadding = false
    }

    private fun h1(t: CharSequence, size: Int = 104) = text(t, size, Color.WHITE, 700).apply { setLineSpacing(0f, 1.1f); letterSpacing = -.03f }

    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun divider() = View(this).apply { setBackgroundColor(Pal.LINE) }

    private fun dim(v: Int) = if (v < 0) v else v.ipx()

    private fun lp(w: Int, h: Int, top: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(dim(w), dim(h)).apply { topMargin = top.ipx(); marginStart = start.ipx() }

    /** 가는 선 테두리. on이면 색 테두리 + 같은 색으로 은은하게 빛남 */
    private fun outline(v: View, color: Int, on: Boolean, radius: Int, fill: Int = Pal.alpha(Color.WHITE, .015f)): View {
        v.background = GradientDrawable().apply {
            setColor(fill); cornerRadius = radius.px(); setStroke(2.ipx().coerceAtLeast(1), if (on) color else Pal.LINE)
        }
        if (on) { v.elevation = 30.px(); v.outlineAmbientShadowColor = color; v.outlineSpotShadowColor = color }
        return v
    }

    private fun button(label: String, color: Int, primary: Boolean, icon: String? = null, onClick: () -> Unit): View = row().apply {
        setPadding(60.ipx(), 34.ipx(), 60.ipx(), 34.ipx())
        if (icon != null) addView(IconView(context, icon, color), lp(50, 50).apply { marginEnd = 14.ipx() })
        addView(text(label, 46, if (primary) color else Color.WHITE, 600))
        outline(this, color, primary, 999)
        if (!primary) (background as GradientDrawable).setStroke(2.ipx().coerceAtLeast(1), Pal.LINE2)
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "GatiTa"
        private const val CLAMP_SAY_GAP_MS = 8000L
        private const val HEARD_HINT = "예) “할머니랑 애들 둘, 강아지도 같이 가”"
        /** 탑승자를 묻는 동안 대답 없이 이만큼 주행하면 혼자 운전으로 보고 시작합니다 */
        private const val ASK_TIMEOUT_DRIVE_SEC = 10
        private const val SOLO_ALERT_SEC = 60      // 시연용. 실제 서비스는 연속 주행 시간·시간대·응답 지연으로 판단
        private const val SIM_SPEED = 10           // 시뮬레이션 경로: 1초에 10초씩 줄어듦

        /**
         * "할머니랑 애들 둘, 강아지도 같이 가" → 좌석별 탑승자. 알아들은 사람이 없으면 null.
         * 운전석은 어른, 조수석은 어르신·어른 한 명, 아이·반려동물은 뒷좌석(창가 먼저, 가운데 마지막).
         */
        internal fun parseRiders(t: String): Map<Seat, Rider>? {
            val kidWords = listOf("아이", "애들", "애기", "아기", "아들", "딸", "손주", "애")
            val elderWords = listOf("할머니", "할아버지", "어르신", "부모님")
            val adultWords = listOf("엄마", "아빠", "남편", "아내", "와이프", "친구")
            val petWords = listOf("강아지", "고양이", "반려", "멍멍이")
            // 숫자 말("둘", "세 명")이 있으면 그 수를 씁니다
            val number = when {
                Regex("넷|네 ?명").containsMatchIn(t) -> 4
                Regex("셋|세 ?명").containsMatchIn(t) -> 3
                Regex("둘|두 ?명").containsMatchIn(t) -> 2
                else -> null
            }
            val hasKid = kidWords.any { t.contains(it) }
            val hasFriend = t.contains("친구")
            // "아들 하나 딸 하나"처럼 아이를 하나씩 말하면 그 횟수를 셉니다
            val namedKids = listOf("아들", "딸", "손주").sumOf { w -> Regex(w).findAll(t).count() }
            val kids = when {
                !hasKid -> 0
                number != null && !hasFriend -> number
                namedKids > 1 -> namedKids
                t.contains("애들") -> 2
                else -> 1
            }
            val elders = elderWords.count { t.contains(it) }
            val adults = adultWords.count { t.contains(it) } + (if (hasFriend && number != null && !hasKid) number - 1 else 0)
            val pet = petWords.any { t.contains(it) }
            if (kids == 0 && elders == 0 && adults == 0 && !pet && !t.contains("혼자")) return null

            val r = Seat.values().associateWith { Rider.NONE }.toMutableMap()
            r[Seat.D] = Rider.ADULT
            val front = MutableList(elders) { Rider.ELDER } + List(adults) { Rider.ADULT }
            val back = MutableList(kids) { Rider.KID } + (if (pet) listOf(Rider.PET) else emptyList())
            // "조수석에 아이", "할머니는 앞자리"처럼 조수석을 직접 말하면 그 사람을 조수석에 앉힙니다
            val asked = frontSeatRider(t, kidWords, elderWords, adultWords, petWords)
            val everyone = (front + back).toMutableList()
            if (asked != null && everyone.remove(asked)) {
                r[Seat.P] = asked
                listOf(Seat.RL, Seat.RR, Seat.RM).zip(everyone.filter { it == Rider.KID || it == Rider.PET } +
                    everyone.filter { it == Rider.ELDER || it == Rider.ADULT }).forEach { (s, who) -> r[s] = who }
                return r
            }
            // 따로 말하지 않으면: 어르신·어른이 조수석, 아이·반려동물은 뒷좌석
            if (front.isNotEmpty()) r[Seat.P] = front.first()
            listOf(Seat.RL, Seat.RR, Seat.RM).zip(back + front.drop(1)).forEach { (s, who) -> r[s] = who }
            return r
        }

        private val frontSeatWords = listOf("조수석", "앞자리", "앞좌석", "옆자리", "보조석")

        /** 조수석을 말한 마디(쉼표·'그리고'·'하고'·'랑'으로 나눈 부분)에 나온 탑승자. 없으면 null */
        private fun frontSeatRider(t: String, kid: List<String>, elder: List<String>, adult: List<String>, pet: List<String>): Rider? {
            val clause = t.split(Regex("[,，.]|그리고|하고|랑")).firstOrNull { c -> frontSeatWords.any { c.contains(it) } } ?: return null
            // 마디 안에서 먼저 나온 사람 기준
            return listOf(Rider.KID to kid, Rider.ELDER to elder, Rider.ADULT to adult, Rider.PET to pet)
                .mapNotNull { (who, words) -> words.mapNotNull { w -> clause.indexOf(w).takeIf { it >= 0 } }.minOrNull()?.let { it to who } }
                .minByOrNull { it.first }?.second
        }
    }
}
