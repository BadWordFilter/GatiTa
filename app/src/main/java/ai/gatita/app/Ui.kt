package ai.gatita.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 같이타 화면 부품. 값은 design/mockups.html 시안과 같고, 시안 기준 화면은 2560×1440입니다.
 * 시안의 px 값을 그대로 쓰고 px()로 실제 화면 크기에 맞춥니다.
 */

/** 시안(2560×1440) 1px → 실제 화면 px. MainActivity.onCreate에서 정합니다 */
var UI_SCALE = 1f
fun Number.px(): Float = toFloat() * UI_SCALE
fun Number.ipx(): Int = px().roundToInt()

/** 색 토큰 */
object Pal {
    val BG = 0xFF121019.toInt()
    val BG2 = 0xFF1A1729.toInt()
    val CARD = 0xFF15131F.toInt()
    val LINE = 0x21FFFFFF          // 흰색 13%
    val LINE2 = 0x52FFFFFF         // 흰색 32%
    val DIM = 0xFF9D97B0.toInt()
    val MUTE = 0xFF5F5A73.toInt()
    val KID = 0xFF8EA2FF.toInt()
    val PET = 0xFFF29BC4.toInt()
    val SOLO = 0xFFB7A4F5.toInt()
    val ELDER = 0xFF8FD9C0.toInt()
    val CHOICE = 0xFF8FD9C0.toInt()
    val REST = 0xFFFFCF9E.toInt()
    val ALERT = 0xFFFF7468.toInt()
    val NEON = 0xFFFFE3C2.toInt()
    val BELT = 0xFF5F5B70.toInt()
    fun alpha(c: Int, a: Float): Int = (c and 0xFFFFFF) or ((a * 255).roundToInt() shl 24)
}

enum class Seat { D, P, RL, RM, RR }

/** 탑승자. 순서가 좌석을 누를 때 바뀌는 순서입니다 (? → 어른 → 아이 → 어르신 → 반려동물) */
enum class Rider(val color: Int) { NONE(Pal.KID), ADULT(Pal.SOLO), KID(Pal.KID), ELDER(Pal.ELDER), PET(Pal.PET) }

/** 대문자 SVG 경로(M L H V Q C S Z)만 읽는 작은 변환기. 시안의 path 문자열을 그대로 씁니다 */
fun svgPath(d: String): Path {
    val p = Path()
    val t = Regex("[MLHVQCSZ]|-?[0-9]*\\.?[0-9]+").findAll(d).map { it.value }.toList()
    var i = 0
    var cmd = 'M'
    var x = 0f; var y = 0f; var c2x = 0f; var c2y = 0f
    fun n() = t[i++].toFloat()
    while (i < t.size) {
        if (t[i][0].isLetter()) {
            cmd = t[i][0]; i++
            if (cmd == 'Z') { p.close(); continue }
        }
        when (cmd) {
            'M' -> { x = n(); y = n(); p.moveTo(x, y); cmd = 'L' }
            'L' -> { x = n(); y = n(); p.lineTo(x, y) }
            'H' -> { x = n(); p.lineTo(x, y) }
            'V' -> { y = n(); p.lineTo(x, y) }
            'Q' -> { val a = n(); val b = n(); x = n(); y = n(); p.quadTo(a, b, x, y) }
            'C' -> { val a = n(); val b = n(); c2x = n(); c2y = n(); x = n(); y = n(); p.cubicTo(a, b, c2x, c2y, x, y) }
            'S' -> {
                val a = 2 * x - c2x; val b = 2 * y - c2y
                c2x = n(); c2y = n(); x = n(); y = n(); p.cubicTo(a, b, c2x, c2y, x, y)
            }
            else -> i++
        }
    }
    return p
}

/** 탑승자 픽토그램 (100×100 기준): 머리 + 몸통만 */
object Fig {
    private val adult = svgPath("M22 96V68Q22 44 50 44Q78 44 78 68V96Z")
    private val kid = svgPath("M31 96V77Q31 58 50 58Q69 58 69 77V96Z")
    private val elder = svgPath("M18 96V72Q18 46 44 46Q66 48 70 72V96Z")
    private val cane = svgPath("M78 96V60Q78 52 85 52")
    private val dog = svgPath("M20 22Q22 12 34 12Q46 12 48 24L50 40Q62 44 70 58Q80 70 78 86Q86 86 90 76Q93 70 96 72Q96 84 86 92H28Q24 92 24 88L26 56Q22 46 26 38L14 36Q6 34 8 28Q10 24 20 22Z")
    val dogEar = svgPath("M36 18Q46 18 46 30Q46 42 40 44Q35 40 35 30Z")
    val dogBody get() = dog

    fun draw(c: Canvas, r: Rider, x: Float, y: Float, size: Float, p: Paint, color: Int = r.color) {
        c.save(); c.translate(x, y); c.scale(size / 100f, size / 100f)
        p.style = Paint.Style.FILL; p.color = color; p.pathEffect = null
        when (r) {
            Rider.KID -> { c.drawCircle(50f, 42f, 12f, p); c.drawPath(kid, p) }
            Rider.ELDER -> {
                c.drawCircle(44f, 27f, 13f, p); c.drawPath(elder, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.strokeCap = Paint.Cap.ROUND; c.drawPath(cane, p)
            }
            Rider.PET -> { c.drawPath(dog, p); p.color = Pal.alpha(Color.BLACK, .22f); c.drawPath(dogEar, p) }
            else -> { c.drawCircle(50f, 24f, 15f, p); c.drawPath(adult, p) }
        }
        c.restore()
    }
}

/** 가는 선 아이콘 (24 그리드) */
object Icons {
    private val paths = mapOf(
        "home" to listOf("M4 11L12 4L20 11V20H15V14H9V20H4Z"),
        "users" to listOf("M3 20Q3 14 9 14Q15 14 15 20", "M16.5 14Q21 14.5 21 19"),
        "book" to listOf("M4 5.5Q4 3 6.5 3H20V18H6.5Q4 18 4 20.5Z", "M4 20.5Q4 23 6.5 23H20V18"),
        "window" to listOf("M4 20V9Q4 4 10 4H18Q20 4 20 6V20Z"),
        "cup" to listOf("M5 9H16V14Q16 19 11 19H10Q5 19 5 14Z", "M16 10H17.5Q20 10 20 12.5Q20 15 17.5 15H16", "M8 3V5M12 3V5"),
        "light" to listOf("M9 18H15M10 21H14", "M12 3Q6 3 6 9Q6 12 8.5 13.9Q9.5 14.8 9.5 16H14.5Q14.5 14.8 15.5 13.9Q18 12 18 9Q18 3 12 3Z"),
        "cave" to listOf("M3 20L8.5 9L11.5 13L14.5 7L21 20Z", "M10 20V18Q10 16 12 16Q14 16 14 18V20"),
        "forest" to listOf("M8 3L4 10H12Z", "M8 10V20", "M16 7L11 16H21Z", "M16 16V20"),
        "plus" to listOf("M12 5V19M5 12H19"),
        "door" to listOf("M6 21V5Q6 3 8 3H16Q18 3 18 5V21M3 21H21"),
    )
    private val circles = mapOf("users" to listOf(floatArrayOf(9f, 8f, 3.5f), floatArrayOf(17f, 9f, 2.5f)))
    private val dashed = mapOf("window" to svgPath("M4 12H20"))
    private val cache = HashMap<String, List<Path>>()

    fun draw(c: Canvas, name: String, p: Paint) {
        cache.getOrPut(name) { paths[name].orEmpty().map(::svgPath) }.forEach { c.drawPath(it, p) }
        circles[name]?.forEach { c.drawCircle(it[0], it[1], it[2], p) }
        dashed[name]?.let { val e = p.pathEffect; p.pathEffect = DashPathEffect(floatArrayOf(2f, 3f), 0f); c.drawPath(it, p); p.pathEffect = e }
    }
}

/** 설계 좌표(w×h)를 뷰 안에 맞춰 그리는 공통 부모. slice=true면 꽉 채우고 넘치는 부분은 잘림 */
abstract class DesignView(ctx: Context, private val dw: Float, private val dh: Float, private val slice: Boolean = false) : View(ctx) {
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    protected var k = 1f
        private set
    protected var ox = 0f
        private set
    protected var oy = 0f
        private set

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // 빛번짐(shadowLayer·blur)을 도형에도 쓰기 위해

    final override fun onDraw(c: Canvas) {
        k = if (slice) max(width / dw, height / dh) else min(width / dw, height / dh)
        ox = (width - dw * k) / 2; oy = (height - dh * k) / 2
        c.save(); c.translate(ox, oy); c.scale(k, k)
        paint(c)
        c.restore()
    }

    abstract fun paint(c: Canvas)

    protected fun stroke(color: Int, w: Float, glow: Float = 0f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.pathEffect = null
        if (glow > 0) p.setShadowLayer(glow, 0f, 0f, color) else p.clearShadowLayer()
    }

    protected fun fill(color: Int, glow: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = color; p.pathEffect = null
        if (glow > 0) p.setShadowLayer(glow, 0f, 0f, color) else p.clearShadowLayer()
    }
}

class IconView(ctx: Context, var name: String, var color: Int, var glow: Boolean = false) : DesignView(ctx, 24f, 24f) {
    override fun paint(c: Canvas) = Icons.draw(c, name, p.also { stroke(color, 1.6f, if (glow) 1.2f else 0f) })
}

/** 탑승자 픽토그램 하나 (말풍선 아바타 등) */
class FigView(ctx: Context, var rider: Rider, var color: Int = rider.color) : DesignView(ctx, 100f, 100f) {
    override fun paint(c: Canvas) { p.setShadowLayer(8f, 0f, 0f, color); Fig.draw(c, rider, 0f, 0f, 100f, p, color) }
}

/** 로고: 어른·아이 픽토그램이 나란히, 각자 안전벨트(회색 띠 + 버클)를 맴 */
class LogoView(ctx: Context) : DesignView(ctx, 130f, 100f) {
    private val bigTorso = svgPath("M16 98V66Q16 42 44 42Q72 42 72 66V98Z")
    private val smallTorso = svgPath("M78 98V80Q78 62 98 62Q118 62 118 80V98Z")

    override fun paint(c: Canvas) {
        person(c, Pal.KID, 44f, 22f, 15f, bigTorso, 20f, 46f, 76f, 100f, 55.5f, 76f)
        person(c, Pal.REST, 98f, 46f, 11f, smallTorso, 80f, 64f, 122f, 102f, 104.5f, 86f)
    }

    private fun person(c: Canvas, color: Int, hx: Float, hy: Float, hr: Float, torso: Path,
                       bx1: Float, by1: Float, bx2: Float, by2: Float, kx: Float, ky: Float) {
        fill(color); c.drawCircle(hx, hy, hr, p); c.drawPath(torso, p)
        c.save(); c.clipPath(torso)
        stroke(Pal.BELT, 9f); p.strokeCap = Paint.Cap.BUTT; c.drawLine(bx1, by1, bx2, by2, p); p.strokeCap = Paint.Cap.ROUND
        c.restore()
        c.save(); c.rotate(42f, kx, ky)
        val r = RectF(kx - 5.5f, ky - 4f, kx + 5.5f, ky + 4f)
        fill(0xFFC9C5D6.toInt()); c.drawRoundRect(r, 2f, 2f, p)
        stroke(0xFF8C88A0.toInt(), 1.2f); c.drawRoundRect(r, 2f, 2f, p)
        c.restore()
    }
}

/** 화면 맨 아래 얇은 빛줄 = 지금 차량 엠비언트 조명 색 */
class AmbientLine(ctx: Context) : View(ctx) {
    var color = Pal.KID
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        val l = width * .1f; val r = width * .9f; val h = height.toFloat()
        p.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        p.maskFilter = BlurMaskFilter(30.px().coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        c.drawRect(l, h - 24.px(), r, h, p)
        p.maskFilter = null
        c.drawRect(l, h - 4.px(), r, h, p)
    }
}

/** 큰 음성 파형. level(0~1)에 마이크 소리 크기를 넣으면 막대가 따라 움직이고, 말이 멈추면 잦아듭니다 */
class WaveView(ctx: Context, var color: Int, private val bars: Int = 56) : View(ctx) {
    @Volatile var level = 0f
    private var shown = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    // GPU로 그립니다(API 28+는 하드웨어 가속에서도 setShadowLayer 지원). 소프트웨어 모드로 초당 60번
    // 빛번짐을 그리면 CPU를 크게 차지해, 바로 그때 나오는 안내 음성이 오디오 출력 지연으로 끊겼습니다.

    override fun onDraw(c: Canvas) {
        shown += (level - shown) * .25f
        level *= .9f
        val t = System.currentTimeMillis() / 1000f
        val gap = width / bars.toFloat()
        p.strokeWidth = gap * .5f; p.color = color; p.setShadowLayer(p.strokeWidth, 0f, 0f, color)
        val mid = height / 2f; val maxH = height / 2f - p.strokeWidth
        for (i in 0 until bars) {
            val env = sin(PI * i / (bars - 1)).toFloat()
            val wob = .5f + .5f * sin(t * 6 + i * .7f)
            val h = max(1f, maxH * (.06f + (.15f + .85f * shown) * wob) * env)
            val x = gap * (i + .5f)
            c.drawLine(x, mid - h, x, mid + h, p)
        }
        if (isShown) postInvalidateDelayed(FRAME_MS)   // 30fps면 파형 움직임은 충분히 부드럽습니다
    }

    private companion object { const val FRAME_MS = 33L }
}

/** 이번 이야기 진행 링: 테두리가 지나간 만큼 = 들은 만큼, 끝에 빛나는 점 */
class RingView(ctx: Context, private val color: Int) : DesignView(ctx, 640f, 640f) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun paint(c: Canvas) {
        val r = 300f
        stroke(Pal.alpha(Color.WHITE, .12f), 8f); c.drawCircle(320f, 320f, r, p)
        stroke(color, 12f, 14f); c.drawArc(RectF(20f, 20f, 620f, 620f), -90f, 360f * progress, false, p)
        val a = Math.toRadians(360.0 * progress - 90.0)
        fill(Color.WHITE, 16f); c.drawCircle(320f + r * cos(a).toFloat(), 320f + r * sin(a).toFloat(), 18f, p)
    }
}

/** 위에서 본 차(가로, 오른쪽이 앞). 빈자리는 점선 + 물음표, 탄 자리는 그 사람 색으로 빛남. 눌러서 바꿀 수 있음 */
/**
 * 위에서 본 차와 좌석. 앞이 위쪽(화면의 질문·파형 쪽)을 향해, 차에 앉아 앞을 보는 방향과 같습니다.
 * 운전석(왼쪽 앞)은 항상 운전자라 고르지 않고, 나머지 좌석만 눌러서 바꿉니다.
 */
class SeatMapView(ctx: Context, private val riders: Map<Seat, Rider>, private val onTap: (Seat) -> Unit) : DesignView(ctx, 560f, 720f) {
    private val rects = mapOf(
        Seat.D to RectF(110f, 230f, 260f, 360f), Seat.P to RectF(300f, 230f, 450f, 360f),
        Seat.RL to RectF(96f, 430f, 214f, 550f), Seat.RM to RectF(221f, 430f, 339f, 550f), Seat.RR to RectF(346f, 430f, 464f, 550f),
    )
    // 앞유리(위)와 뒷유리(아래) 곡선
    private val curves = svgPath("M110 150Q280 95 450 150M120 630Q280 670 440 630")
    private val q = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 72f; color = Pal.KID; typeface = Typeface.create(Typeface.DEFAULT, 200, false)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 26f; color = Pal.MUTE; typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }

    init { contentDescription = "운전석을 뺀 좌석을 눌러 탑승자를 고르세요. 위쪽이 차의 앞입니다" }

    override fun paint(c: Canvas) {
        stroke(Pal.alpha(Color.WHITE, .4f), 2.6f)
        c.drawRoundRect(RectF(40f, 20f, 520f, 700f), 200f, 200f, p)
        c.drawPath(curves, p)
        // 운전대: 운전석 위
        stroke(Pal.alpha(Color.WHITE, .55f), 4f)
        c.drawCircle(185f, 190f, 26f, p); c.drawLine(159f, 190f, 211f, 190f, p); c.drawLine(185f, 190f, 185f, 216f, p)
        rects.forEach { (seat, r) ->
            val who = riders[seat] ?: Rider.NONE
            when {
                seat == Seat.D -> {
                    // 운전자: 고정, 흐린 색으로 표시
                    fill(Pal.alpha(Color.WHITE, .04f)); c.drawRoundRect(r, 32f, 32f, p)
                    stroke(Pal.alpha(Color.WHITE, .35f), 3f); c.drawRoundRect(r, 32f, 32f, p)
                    p.clearShadowLayer()
                    Fig.draw(c, Rider.ADULT, r.centerX() - 40f, r.centerY() - 52f, 80f, p, Pal.alpha(Color.WHITE, .55f))
                    c.drawText("운전자", r.centerX(), r.bottom - 14f, label)
                }
                who == Rider.NONE -> {
                    fill(Pal.alpha(Pal.KID, .05f)); c.drawRoundRect(r, 32f, 32f, p)
                    stroke(Pal.KID, 3f, 8f); p.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f); c.drawRoundRect(r, 32f, 32f, p)
                    c.drawText("?", r.centerX(), r.centerY() + 26f, q)
                }
                else -> {
                    fill(Pal.alpha(who.color, .08f)); c.drawRoundRect(r, 32f, 32f, p)
                    stroke(who.color, 3.5f, 10f); c.drawRoundRect(r, 32f, 32f, p)
                    val z = if (who == Rider.KID) 76f else 92f
                    p.clearShadowLayer()
                    Fig.draw(c, who, r.centerX() - z / 2, r.centerY() - z / 2 + 4f, z, p)
                }
            }
        }
    }

    private fun hit(e: MotionEvent): Seat? {
        val x = (e.x - ox) / k; val y = (e.y - oy) / k
        return rects.entries.firstOrNull { it.key != Seat.D && it.value.contains(x, y) }?.key
    }
    private val gestures = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hit(e)?.let { onTap(it); performClick() }
            return true
        }

    })
    override fun onTouchEvent(e: MotionEvent): Boolean = isEnabled && gestures.onTouchEvent(e)

    override fun performClick(): Boolean = super.performClick()
}

/** 옆에서 본 선 그림 좌석 + 앉은 탑승자 */
class SeatFigureView(ctx: Context, private val rider: Rider) : DesignView(ctx, 220f, 220f) {
    private val seat = svgPath("M40 22Q58 12 66 28L62 50Q46 56 38 42Z M44 62Q66 56 70 74L64 148L170 150Q190 152 190 168L188 180L60 180Q38 180 38 160Z M80 180L74 206M168 180L174 206")

    override fun paint(c: Canvas) {
        stroke(Pal.alpha(Color.WHITE, .45f), 2.4f); c.drawPath(seat, p)
        p.setShadowLayer(8f, 0f, 0f, rider.color)
        Fig.draw(c, rider, 70f, 40f, 110f, p)
    }
}

/** 반려동물 창문: 위쪽 좁은 틈(환기만), 강아지는 유리 너머(흐리게 + 점선), 아래는 창틀에 가려짐 */
class WindowView(ctx: Context) : DesignView(ctx, 1000f, 460f) {
    private val frame = svgPath("M120 420V170Q120 50 260 50H860Q880 50 880 70V420Z")
    private val gapY = 92f

    override fun paint(c: Canvas) {
        fill(Pal.alpha(Color.WHITE, .015f)); c.drawPath(frame, p)
        c.save(); c.clipPath(frame)
        fill(0xFF1E1A2E.toInt()); c.drawRect(122f, gapY, 878f, 418f, p)
        c.save(); c.translate(350f, 196f); c.scale(3f, 3f)
        fill(Pal.alpha(Pal.PET, .38f)); c.drawPath(Fig.dogBody, p)
        stroke(Pal.PET, 1.1f); p.pathEffect = DashPathEffect(floatArrayOf(3f, 2.2f), 0f); c.drawPath(Fig.dogBody, p)
        fill(Pal.alpha(Pal.PET, .25f)); c.drawPath(Fig.dogEar, p)
        c.restore()
        fill(0x0DB4AAE6); c.drawRect(122f, gapY, 878f, 418f, p)
        stroke(Pal.PET, 4f, 8f); p.pathEffect = DashPathEffect(floatArrayOf(14f, 12f), 0f); c.drawLine(122f, gapY, 878f, gapY, p)
        c.restore()
        stroke(Pal.alpha(Color.WHITE, .45f), 3f); c.drawPath(frame, p)
    }
}

/** 하차 알림: 위에서 본 차. 열린 운전석 문과 아직 아이(반려동물)가 있는 뒷좌석이 빛남 */
class CarTopView(ctx: Context, private val kids: Int, private val pet: Boolean) : DesignView(ctx, 370f, 520f) {
    private val curves = svgPath("M130 140Q220 110 310 140M130 440Q220 465 310 440")

    override fun paint(c: Canvas) {
        c.translate(70f, 0f) // 왼쪽으로 열린 문을 그릴 자리
        stroke(Pal.alpha(Color.WHITE, .45f), 2.4f)
        c.drawRoundRect(RectF(30f, 20f, 270f, 500f), 110f, 110f, p)
        c.save(); c.translate(-70f, 0f); c.drawPath(curves, p); c.restore()
        c.drawRoundRect(RectF(64f, 170f, 138f, 266f), 22f, 22f, p)
        c.drawRoundRect(RectF(162f, 170f, 236f, 266f), 22f, 22f, p)
        stroke(Pal.ALERT, 4f, 8f); c.drawLine(30f, 186f, -30f, 244f, p)
        val rear = RectF(64f, 310f, 236f, 420f)
        fill(Pal.alpha(Pal.ALERT, .08f)); c.drawRoundRect(rear, 26f, 26f, p)
        stroke(Pal.ALERT, 4f, 14f); c.drawRoundRect(rear, 26f, 26f, p)
        val who = List(kids.coerceIn(0, 3)) { Rider.KID } + if (pet) listOf(Rider.PET) else emptyList()
        val shown = who.take(3)
        if (shown.isEmpty()) return
        val slot = rear.width() / shown.size
        val size = min(88f, slot - 6f)
        p.clearShadowLayer()
        shown.forEachIndexed { i, r -> Fig.draw(c, r, rear.left + slot * i + (slot - size) / 2, rear.centerY() - size / 2, size, p) }
    }
}

/** 내비 지도(분위기용 선 지도): 경로, 내 차, 휴게소 핀. shortcut: 실제 지도 아님, NaviHelper가 지도 화면을 주면 교체 */
class NavMapView(ctx: Context) : DesignView(ctx, 1400f, 1060f, slice = true) {
    var showPin = false
        set(v) { field = v; invalidate() }
    private val roads = svgPath("M-20 180H1420M-20 420H1420M-20 700H1420M-20 930H1420M200 -20V1080M520 -20V1080M980 -20V1080M1240 -20V1080")
    private val lanes = svgPath("M-20 300H1420M-20 560H1420M-20 820H1420M360 -20V1080M760 -20V1080M1110 -20V1080")
    private val route = svgPath("M700 1100C700 900 640 820 700 700S900 520 860 380S760 160 820 -20")
    private val branch = svgPath("M868 470Q960 450 1010 410")
    private val arrow = svgPath("M0 -34L26 28L0 14L-26 28Z")
    private val pin = svgPath("M0 0C-12 -40 -58 -62 -58 -108L58 -108C58 -62 12 -40 0 0Z")

    override fun paint(c: Canvas) {
        fill(Pal.CARD); c.drawRect(-200f, -200f, 1600f, 1260f, p)
        stroke(Pal.alpha(Color.WHITE, .07f), 10f); c.drawPath(roads, p)
        stroke(Pal.alpha(Color.WHITE, .04f), 4f); c.drawPath(lanes, p)
        stroke(Pal.alpha(Color.WHITE, .16f), 34f); c.drawPath(route, p)
        stroke(Pal.SOLO, 14f, 10f); c.drawPath(route, p)
        if (showPin) {
            stroke(Pal.REST, 10f, 8f); p.pathEffect = DashPathEffect(floatArrayOf(4f, 18f), 0f); c.drawPath(branch, p)
            c.save(); c.translate(1030f, 400f)
            fill(Pal.REST, 16f); c.drawPath(pin, p); c.drawCircle(0f, -108f, 58f, p)
            fill(Pal.CARD); c.drawCircle(0f, -108f, 24f, p)
            c.restore()
        }
        c.save(); c.translate(700f, 880f)
        fill(Pal.alpha(Pal.SOLO, .18f)); c.drawCircle(0f, 0f, 56f, p)
        fill(Color.WHITE, 10f); c.drawPath(arrow, p)
        c.restore()
    }
}
