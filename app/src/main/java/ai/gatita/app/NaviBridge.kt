package ai.gatita.app

import android.content.Context
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** Pleos NaviHelper SDK 래퍼: 남은 시간, 현재 위치, 도착, 휴게소 경유지 추가 */
class NaviBridge(context: Context, private val events: Events) {

    interface Events {
        fun onRemainSeconds(sec: Int, destination: String)
        fun onAddress(address: String)
        fun onArrived()
        fun onLog(msg: String)
    }

    private val navi = NaviHelper(context)
    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    @Volatile private var heading: Double? = null
    @Volatile private var hasRoute = false

    private val listener = object : NaviHelperEventListener {
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            hasRoute = true
            val d = drivingInfo.destination
            events.onRemainSeconds(d.duration, d.name)
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            lat = currentLocationInfo.latitude; lon = currentLocationInfo.longitude
            heading = currentLocationInfo.heading
            events.onAddress(currentLocationInfo.address)
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { hasRoute = false; events.onArrived() }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { hasRoute = true; events.onLog("내비 경로 안내 시작") }
        override fun onRouteCancelled() { hasRoute = false }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { events.onLog("경유지 변경됨") }
        override fun onError(naviErrorCode: NaviErrorCode) { events.onLog("내비 오류: $naviErrorCode") }
    }

    fun start() {
        try {
            navi.initialize({
                runCatching {
                    navi.addListener(listener)
                    navi.getCurrentLocationInfo()
                }.onFailure { events.onLog("NaviHelper 요청 실패: ${it.message}") }
            }, { t -> events.onLog("NaviHelper 연결 실패: ${t.message}") })
        } catch (t: Throwable) {
            events.onLog("NaviHelper 오류: ${t.message}")
        }
    }

    /** 지금 위치에서 가장 가까운 휴게소와 직선거리(km). 위치를 모르면 거리는 null */
    fun nearestStop(): Pair<RestStops.Stop, Double?> {
        val la = lat; val lo = lon
        val stop = RestStops.nearestAhead(la, lo, heading)
        return stop to if (la != null && lo != null) RestStops.km(la, lo, stop.lat, stop.lon) else null
    }

    /**
     * 현재 위치에서 가장 가까운 휴게소를 경유지로 추가합니다.
     * 안내 중인 경로가 없으면 휴게소를 목적지로 경로 안내를 시작합니다.
     */
    fun addRestStop(): RestStops.Stop {
        val stop = RestStops.nearestAhead(lat, lon, heading)
        runCatching {
            if (hasRoute) {
                navi.addWaypoint(RequestWaypointInfo(stop.lat, stop.lon, stop.name, WaypointIndex.FIRST, "", "", "", ""))
                events.onLog("경유지 추가 요청: ${stop.name}")
            } else {
                navi.requestRoute(RouteInfo(stop.lat, stop.lon, null, null, stop.name, "", "", "", RouteOption.RECOMMENDED, "", false))
                events.onLog("경로 요청: ${stop.name}")
            }
        }.onFailure { events.onLog("휴게소 안내 실패: ${it.message}") }
        return stop
    }

    /** 현재 위치(진행 방향 포함)를 다시 요청합니다. 결과는 onCurrentLocationInfo로 옵니다 */
    fun requestLocation() {
        runCatching { navi.getCurrentLocationInfo() }.onFailure { events.onLog("위치 요청 실패: ${it.message}") }
    }

    fun release() = runCatching { navi.removeListener(listener); navi.release() }
}
