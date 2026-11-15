package ai.gatita.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 경부고속도로 수도권·충청 구간 휴게소 (좌표: 네이버 지도 기준, WGS84) */
object RestStops {

    data class Stop(val name: String, val lat: Double, val lon: Double)

    val all = listOf(
        Stop("서울만남의광장휴게소(부산방향)", 37.4602298, 127.0419172),
        Stop("죽전휴게소(서울방향)", 37.3327312, 127.1049040),
        Stop("안성휴게소(서울방향)", 37.0767837, 127.1324553),
        Stop("안성휴게소(부산방향)", 37.0135394, 127.1447656),
        Stop("청주휴게소(서울방향)", 36.7159592, 127.3494116),
        Stop("옥산휴게소(부산방향)", 36.6577160, 127.3698037),
        Stop("죽암휴게소(서울방향)", 36.4968987, 127.4308903),
        Stop("죽암휴게소(부산방향)", 36.4868923, 127.4297437),
    )

    /** 현재 위치에서 가장 가까운 휴게소. 위치를 모르면 첫 번째 휴게소 */
    fun nearest(lat: Double?, lon: Double?): Stop {
        if (lat == null || lon == null) return all.first()
        return all.minBy { km(lat, lon, it.lat, it.lon) }
    }

    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
