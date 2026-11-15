package ai.gatita.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "오늘 누구와 함께하시나요?"에 대한 대답 문장이 좌석에 어떻게 채워지는지 확인합니다 */
class RiderParseTest {

    private fun seats(t: String): String {
        val r = MainActivity.parseRiders(t) ?: return "(못 알아들음)"
        return Seat.values().filter { r[it] != Rider.NONE }.joinToString(" ") { "${it.name}=${r[it]!!.name}" }
    }

    @Test fun report() {
        val samples = listOf(
            "혼자", "혼자 가요", "애들이랑", "아이 둘이랑 가요", "할머니랑 애들 둘, 강아지도 같이 가",
            "엄마랑 딸", "강아지랑", "고양이 데리고 가요", "남편이랑 갑니다", "와이프랑 애들 셋",
            "할아버지 할머니 모시고", "아들 하나 딸 하나", "애 둘", "우리 가족 다 같이", "친구 둘이랑",
            "조수석에 아이 태웠어", "강아지는 앞자리에", "엄마랑 아이, 아이는 조수석", "할머니는 옆자리, 애들 둘은 뒤에",
        )
        val out = samples.joinToString("\n") { "$it  →  ${seats(it)}" }
        File("build/rider-parse-report.txt").writeText(out)
        println(out)
    }

    @Test fun basicCases() {
        assertEquals(Rider.KID, MainActivity.parseRiders("애들이랑")!![Seat.RL])
        assertEquals(Rider.PET, MainActivity.parseRiders("강아지랑")!![Seat.RL])
        assertNull(MainActivity.parseRiders("음 글쎄"))
    }

    private fun count(t: String, who: Rider) = MainActivity.parseRiders(t)!!.values.count { it == who }

    @Test fun counting() {
        assertEquals(2, count("아들 하나 딸 하나", Rider.KID))
        assertEquals(2, count("애 둘", Rider.KID))
        assertEquals(3, count("친구 둘이랑", Rider.ADULT))   // 운전자 + 친구 2명
        assertEquals(2, count("할머니랑 애들 둘, 강아지도 같이 가", Rider.KID))
        assertEquals(3, count("와이프랑 애들 셋", Rider.KID))
    }

    private fun at(t: String, s: Seat) = MainActivity.parseRiders(t)!![s]

    @Test fun passengerSeat() {
        assertEquals(Rider.KID, at("조수석에 아이 태웠어", Seat.P))
        assertEquals(Rider.PET, at("강아지는 앞자리에", Seat.P))
        assertEquals(Rider.KID, at("엄마랑 아이, 아이는 조수석", Seat.P))
        assertEquals(Rider.ADULT, at("엄마랑 아이, 아이는 조수석", Seat.RL))   // 엄마는 뒷좌석으로
        assertEquals(Rider.ELDER, at("할머니는 옆자리, 애들 둘은 뒤에", Seat.P))
        assertEquals(2, count("할머니는 옆자리, 애들 둘은 뒤에", Rider.KID))
        // 조수석을 말하지 않으면 예전 규칙 그대로
        assertEquals(Rider.ELDER, at("할머니랑 애들 둘", Seat.P))
    }

    @Test fun restStopDirection() {
        // 성남 근처에서 남쪽(180도)으로 달리면 부산방향 휴게소 중 남쪽에 있는 곳
        val south = RestStops.nearestAhead(37.40, 127.10, 180.0)
        assertEquals(true, south.name.contains("부산방향") && south.lat < 37.40)
        // 북쪽(0도)으로 달리면 서울방향 휴게소 중 북쪽에 있는 곳이 없으면 가장 가까운 곳
        val north = RestStops.nearestAhead(37.20, 127.10, 0.0)
        assertEquals(true, north.name.contains("서울방향") && north.lat > 37.20)
    }
}
