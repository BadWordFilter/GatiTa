package ai.gatita.app

import kotlin.math.ceil
import kotlin.math.max

/** 남은 도착 시간에 맞춰 이야기 개수를 정하는 분기형 이야기 엔진 */
class Story {

    data class Chapter(val title: String, val text: String)

    private val opening = listOf(
        Chapter("용의 강 탐험 · 1화", "옛날 옛날, 깊은 강 속에 푸른 용이 살았어요. 용은 오늘 아주 먼 곳으로 여행을 떠나기로 했대요."),
        Chapter("용의 강 탐험 · 2화", "용은 길을 가다 작은 다람쥐를 만났어요. 다람쥐는 용에게 반짝이는 도토리 지도를 건네주었어요."),
    )
    private val forest = listOf(
        Chapter("숲속 길 · 3화", "숲에 들어서자 나무들이 속삭였어요. 용아, 이쪽이야! 용은 나무들의 목소리를 따라갔어요."),
        Chapter("숲속 길 · 4화", "숲 한가운데에서 용은 반딧불이 친구들을 만났어요. 반딧불이들이 밤길을 환하게 밝혀 주었어요."),
    )
    private val cave = listOf(
        Chapter("동굴 길 · 3화", "동굴 안은 어둡지만 신기했어요. 천장에서 반짝이는 보석들이 별처럼 빛났어요."),
        Chapter("동굴 길 · 4화", "동굴 끝에서 용은 메아리 요정을 만났어요. 요정이 안녕 하고 말하자, 안녕 안녕 안녕 하고 울렸어요."),
    )
    private val bonus = Chapter("보너스 · 구름 놀이터", "길이 조금 막히네요. 그럼 특별히 구름 놀이터 이야기를 하나 더 들려줄게요. 구름 위에서 용은 미끄럼틀을 탔어요!")
    val ending = Chapter("마지막 화 · 집에 온 용", "그렇게 용은 무사히 집에 도착했어요. 우리도 이제 곧 도착! 문은 어른이 열어 줄 때까지 기다려요.")
    val river = "친구들, 창밖을 봐! 지금 건너는 강이 바로 용이 사는 강이야!"
    val choiceQuestion = "동굴로 갈까, 숲으로 갈까?"

    var branch: List<Chapter>? = null
    private var index = 0
    var planned = 0; private set
    private var slotSec = 600

    /** 첫 남은 시간으로 이야기 개수를 정합니다 (3~12개) */
    fun plan(remainSec: Int) {
        planned = (remainSec / 600).coerceIn(4, 12)
        slotSec = max(30, remainSec / planned)
        index = 0
    }

    fun storiesLeft(remainSec: Int): Int = ceil(remainSec / slotSec.toDouble()).toInt().coerceAtLeast(0)

    /** 다음 챕터. 정체로 남은 이야기 수가 늘었으면 보너스 챕터를 끼워 넣습니다 */
    fun next(isBonus: Boolean): Chapter {
        if (isBonus) return bonus
        val seq = opening + (branch ?: forest)
        val c = seq.getOrElse(index) { bonus.copy(title = "이어지는 이야기 · ${index + 1}화") }
        index++
        return c
    }

    val needsChoice get() = index == opening.size && branch == null

    fun choose(forestPath: Boolean) { branch = if (forestPath) forest else cave }
}
