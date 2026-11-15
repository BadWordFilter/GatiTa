package ai.gatita.app

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.vehicle.constant.area.DoorValue

/**
 * 같이타 – 탑승자 맞춤 음성 동승자.
 * 출발 시 "오늘 누구랑 가요?"로 모드를 정하고, 기어(주행/정차)·도어·창문·내비 남은 시간에 맞춰 동작합니다.
 */
class MainActivity : Activity(), VehicleBridge.Events, NaviBridge.Events {

    enum class Mode { ASK, KID, PET, SOLO }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var vehicle: VehicleBridge
    private lateinit var navi: NaviBridge
    private lateinit var voice: Voice
    private val story = Story()
    // 1600px 기준 레이아웃을 실제 화면 폭에 맞춰 키우는 배율 (Pleos 2560x1440 mdpi에서 1.6배)
    private val k by lazy { (resources.displayMetrics.widthPixels / 1600f / resources.displayMetrics.density).coerceAtLeast(1f) }

    // 상태
    private var mode = Mode.ASK
    private var parked = true
    private var gearLabel = "-"
    private var remainSec = -1
    private var naviLive = false
    private var lastStoriesLeft = -1
    private var riverDone = false
    private var rearOpenedSincePark = false
    private var driveSeconds = 0
    private var soloAlerted = false
    private var awaitingChoice = false
    private var storyStarted = false

    // 뷰
    private lateinit var root: LinearLayout
    private lateinit var main: LinearLayout
    private lateinit var bigText: TextView
    private lateinit var subText: TextView
    private lateinit var ringText: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var alertText: TextView
    private val sig = mutableMapOf<String, TextView>()
    private lateinit var logView: TextView
    private val glow = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF3D6BFF.toInt(), BG))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        voice = Voice(this) { log(it) }.also { it.start() }
        vehicle = VehicleBridge(this, this).also { it.start() }
        navi = NaviBridge(this, this).also { it.start() }

        ui.postDelayed({ ask() }, 1500)
        ui.postDelayed(tick, 1000)
        // 5초 안에 내비 경로 정보가 없으면 시연용 경로(13분 20초)를 시뮬레이션합니다.
        ui.postDelayed({ if (!naviLive && remainSec < 0) { remainSec = 800; setSig("eta", "13분 20초 (시뮬레이션)") } }, 5000)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        voice.release(); vehicle.release(); navi.release()
        super.onDestroy()
    }

    // ───────── 흐름 ─────────

    private fun ask() {
        mode = Mode.ASK
        show("오늘 누구랑 가요?", "말하거나 아래에서 골라 주세요")
        setButtons("👶 아이" to { pick(Mode.KID) }, "🐶 반려동물" to { pick(Mode.PET) }, "🧑 혼자" to { pick(Mode.SOLO) })
        voice.say("오늘 누구랑 가요?")
        ui.postDelayed({
            voice.listen { heard ->
                ui.post {
                    when {
                        heard.contains("아이") || heard.contains("애들") || heard.contains("아들") || heard.contains("딸") -> pick(Mode.KID)
                        heard.contains("강아지") || heard.contains("고양이") || heard.contains("반려") -> pick(Mode.PET)
                        heard.contains("혼자") -> pick(Mode.SOLO)
                    }
                }
            }
        }, 1800)
    }

    private fun pick(m: Mode) {
        if (mode != Mode.ASK) return
        mode = m
        setSig("mode", when (m) { Mode.KID -> "👶 아이"; Mode.PET -> "🐶 반려동물"; Mode.SOLO -> "🧑 혼자"; else -> "-" })
        setButtons()
        when (m) {
            Mode.KID -> {
                val r = if (remainSec > 0) remainSec else 800
                story.plan(r); lastStoriesLeft = story.storiesLeft(r)
                ambient(0x3D6BFF)
                voice.say("좋아요. 도착할 때까지 이야기 ${lastStoriesLeft}개를 준비했어요.")
                show("이야기 ${lastStoriesLeft}개 준비 완료", "출발하면 시작해요")
            }
            Mode.PET -> {
                ambient(0xFFB547)
                vehicle.setRearWindow(PET_WINDOW_LIMIT)
                setSig("window", "뒷창 최대 $PET_WINDOW_LIMIT")
                voice.say("반려동물 모드예요. 뒷좌석 창문 열림을 안전한 높이로 맞췄어요.")
                show("창문 열림을 안전 높이로 맞췄어요", "주행 중 창문이 더 열리면 자동으로 되돌립니다")
            }
            Mode.SOLO -> {
                ambient(0xE8F0FF)
                voice.say("혼자 운전하시는군요. 졸리면 제가 말을 걸어 드릴게요.")
                show("깨어있기 코치 켜짐", "연속 주행 시간을 지켜보고 있어요")
            }
            else -> {}
        }
    }

    /** 1초마다: 남은 시간 갱신(시뮬레이션), 이야기 진행, 졸음 코치 */
    private val tick = object : Runnable {
        override fun run() {
            if (!naviLive && remainSec > 0 && !parked) { remainSec = (remainSec - SIM_SPEED).coerceAtLeast(0); setSig("eta", fmt(remainSec) + " (시뮬레이션)") }
            if (!parked) driveSeconds++
            when (mode) {
                Mode.KID -> kidTick()
                Mode.SOLO -> soloTick()
                else -> {}
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun kidTick() {
        if (remainSec < 0 || parked || awaitingChoice) { refreshKidScreen(); return }
        val left = story.storiesLeft(remainSec)
        if (remainSec == 0 || left == 0) { refreshKidScreen(); return }
        if (!storyStarted) {
            // 출발(D)하는 순간 1화를 바로 시작
            storyStarted = true
            val ch = story.next(false); voice.say(ch.text); setSig("story", ch.title)
            lastStoriesLeft = left
        } else if (left != lastStoriesLeft) {
            val bonus = left > lastStoriesLeft
            lastStoriesLeft = left
            if (!bonus && story.needsChoice) { askChoice(); return }
            val ch = if (left <= 1) story.ending else story.next(bonus)
            voice.say(ch.text)
            setSig("story", ch.title)
        }
        refreshKidScreen()
    }

    private fun askChoice() {
        awaitingChoice = true
        ambient(0x3DDC97)
        voice.say(story.choiceQuestion)
        show("어디로 갈까?", "아이가 말로 고르면 이야기가 바뀌어요")
        val done = { forest: Boolean ->
            if (awaitingChoice) {
                awaitingChoice = false; story.choose(forest); setButtons()
                voice.say(if (forest) "좋아, 숲으로 가자!" else "좋아, 동굴로 가자!")
                val ch = story.next(false); ui.postDelayed({ voice.say(ch.text); setSig("story", ch.title) }, 2500)
            }
        }
        setButtons("🕳️ 동굴" to { done(false) }, "🌲 숲" to { done(true) })
        ui.postDelayed({ voice.listen { h -> ui.post { if (h.contains("숲")) done(true) else if (h.contains("동굴")) done(false) } } }, 1500)
        // 8초 안에 대답이 없으면 숲으로 진행 (주행 중 화면 조작 없이도 이야기가 이어지도록)
        ui.postDelayed({ if (awaitingChoice) { voice.say("대답이 없네? 그럼 숲으로 가 볼까?"); ui.postDelayed({ done(true) }, 2500) } }, 8000)
    }

    private fun refreshKidScreen() {
        if (awaitingChoice) return
        val left = if (remainSec >= 0) story.storiesLeft(remainSec) else 0
        ringText.text = left.toString()
        ringText.visibility = View.VISIBLE
        if (parked) show(sig["story"]?.text?.toString()?.takeIf { it != "-" } ?: "이야기 준비 완료",
            "🅿️ 정차 중 확장 화면 · 도착까지 ${fmt(remainSec.coerceAtLeast(0))}")
        else show("이야기 ${left}개 남음", "주행 중 · 음성 전용 화면")
    }

    private fun soloTick() {
        if (parked || soloAlerted || driveSeconds < SOLO_ALERT_SEC) return
        soloAlerted = true
        ambient(0xFFFFFF)
        voice.say("연속으로 오래 운전하셨어요. 가까운 휴게소에서 15분 쉬어 갈까요?")
        show("잠깐 쉬어 갈까요?", "연속 주행 ${driveSeconds / 60}분 ${driveSeconds % 60}초")
        setButtons("휴게소 안내" to { voice.say("가까운 휴게소를 경유지로 추가할게요."); setButtons() }, "괜찮아요" to { setButtons() })
    }

    // ───────── 차량 이벤트 ─────────

    override fun onGear(parked: Boolean, label: String) = ui.post {
        val wasDriving = !this.parked
        this.parked = parked; gearLabel = label
        setSig("gear", label)
        if (parked && wasDriving) { rearOpenedSincePark = false; driveSeconds = 0; soloAlerted = false }
        if (mode == Mode.KID) refreshKidScreen()
    }.let { }

    override fun onDoor(door: DoorValue, open: Boolean) = ui.post {
        setSig("door_" + door.name, if (open) "열림" else "닫힘")
        if (open && (door == DoorValue.ROW_2_LEFT || door == DoorValue.ROW_2_RIGHT)) { rearOpenedSincePark = true; hideAlert() }
        // 하차 리마인더: 아이/반려동물 모드 + 정차 + 운전석 문 열림 + 뒷문은 아직 안 열림
        if (open && door == DoorValue.ROW_1_LEFT && parked && !rearOpenedSincePark && (mode == Mode.KID || mode == Mode.PET)) {
            val who = if (mode == Mode.KID) "아이" else "반려동물"
            ambient(0xFF5A4E)
            alert("⚠️ 뒷좌석에 ${who}가 있어요!")
            voice.say("잠깐만요! 뒷좌석에 ${who}가 있어요!")
        }
    }.let { }

    override fun onRearWindow(position: Int) = ui.post {
        setSig("window", "뒷창 $position")
        if (mode == Mode.PET && position > PET_WINDOW_LIMIT) {
            vehicle.setRearWindow(PET_WINDOW_LIMIT)
            voice.say("창문이 너무 많이 열렸어요. 안전한 높이로 되돌릴게요.")
        }
    }.let { }

    override fun onRemainSeconds(sec: Int, destination: String) = ui.post {
        naviLive = true; remainSec = sec
        setSig("eta", fmt(sec) + if (destination.isNotEmpty()) " · $destination" else "")
    }.let { }

    override fun onAddress(address: String) = ui.post {
        setSig("location", address)
        if (mode == Mode.KID && !riverDone && (address.contains("강") || address.contains("대교") || address.contains("교량"))) {
            riverDone = true; ambient(0x1FB6FF); voice.say(story.river)
        }
    }.let { }

    override fun onArrived() = ui.post { remainSec = 0; setSig("eta", "도착") }.let { }

    override fun onLog(msg: String) = log(msg)

    // ───────── UI ─────────

    private fun ambient(rgb: Int) {
        vehicle.setAmbient(rgb)
        glow.colors = intArrayOf(0xFF000000.toInt() or rgb, BG)
        setSig("ambient", "● #%06X".format(rgb))
    }

    private fun show(big: String, sub: String) { bigText.text = big; subText.text = sub }
    private fun alert(t: String) { alertText.text = t; alertText.visibility = View.VISIBLE }
    private fun hideAlert() { alertText.visibility = View.GONE }

    private fun setButtons(vararg items: Pair<String, () -> Unit>) {
        buttons.removeAllViews()
        items.forEach { (label, action) ->
            buttons.addView(Button(this).apply {
                text = label; textSize = 26f * k; setTextColor(Color.WHITE); isAllCaps = false
                background = GradientDrawable().apply { setColor(PANEL); cornerRadius = 36f; setStroke(3, LINE) }
                setPadding(60, 40, 60, 40); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(24, 0, 24, 0) })
        }
    }

    private fun setSig(key: String, value: String) { sig[key]?.text = value }

    private fun log(msg: String) = ui.post {
        logView.text = (msg + "\n" + logView.text).take(1200)
    }.let { }

    private fun fmt(sec: Int) = if (sec >= 3600) "${sec / 3600}시간 ${(sec % 3600) / 60}분" else "${sec / 60}분 ${sec % 60}초"

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG) }
        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glow; setPadding(40, 40, 40, 40)
        }
        val title = text("같이타", 22f, Color.WHITE, true).apply { gravity = Gravity.START }
        ringText = text("", 120f, Color.WHITE, true).apply { visibility = View.GONE }
        bigText = text("", 52f, Color.WHITE, true)
        subText = text("", 24f, DIM, false).apply { setPadding(0, 16, 0, 40) }
        alertText = text("", 44f, Color.WHITE, true).apply {
            visibility = View.GONE; setPadding(50, 40, 50, 40)
            background = GradientDrawable().apply { setColor(0xFF5A1E1A.toInt()); cornerRadius = 40f; setStroke(5, 0xFFFF5A4E.toInt()) }
            setOnClickListener { hideAlert() }
        }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        main.addView(title, LinearLayout.LayoutParams(-1, -2))
        listOf(ringText, bigText, subText, buttons).forEach { main.addView(it) }
        main.addView(alertText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 30 })

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28); setBackgroundColor(0xFF0B0E13.toInt()) }
        panel.addView(text("차량 연동 상태", 20f, Color.WHITE, true).apply { gravity = Gravity.START; setPadding(0, 0, 0, 16) })
        listOf(
            "mode" to "탑승 모드", "gear" to "기어 · Vehicle", "eta" to "남은 시간 · NaviHelper", "location" to "위치 · NaviHelper",
            "story" to "이야기", "ambient" to "엠비언트 · Light", "window" to "창문 · Window",
            "door_ROW_1_LEFT" to "운전석 문 · Door", "door_ROW_2_LEFT" to "뒷좌석 왼쪽 문", "door_ROW_2_RIGHT" to "뒷좌석 오른쪽 문",
        ).forEach { (k, label) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 12, 18, 12)
                background = GradientDrawable().apply { setColor(PANEL); cornerRadius = 18f } }
            row.addView(text(label, 14f, DIM, false).apply { gravity = Gravity.START })
            val v = text("-", 18f, Color.WHITE, true).apply { gravity = Gravity.START }
            sig[k] = v; row.addView(v)
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
        logView = text("", 12f, 0xFF5E6B7D.toInt(), false).apply { gravity = Gravity.START }
        panel.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(panel, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.24f).toInt(), -1))
        setContentView(root)
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = size * k; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    companion object {
        private val BG = 0xFF07090D.toInt()
        private val PANEL = 0xFF10141B.toInt()
        private val LINE = 0xFF1E2530.toInt()
        private val DIM = 0xFF8A96A8.toInt()
        private const val PET_WINDOW_LIMIT = 20
        private const val SOLO_ALERT_SEC = 60      // 시연용. 실제 서비스는 연속 주행 시간·시간대·응답 지연으로 판단
        private const val SIM_SPEED = 10           // 시뮬레이션 경로: 1초에 10초씩 줄어듦
    }
}
