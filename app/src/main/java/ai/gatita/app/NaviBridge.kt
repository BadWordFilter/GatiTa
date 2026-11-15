package ai.gatita.app

import android.content.Context
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** Pleos NaviHelper SDK 래퍼: 남은 시간, 현재 위치(주소), 도착 이벤트 */
class NaviBridge(context: Context, private val events: Events) {

    interface Events {
        fun onRemainSeconds(sec: Int, destination: String)
        fun onAddress(address: String)
        fun onArrived()
        fun onLog(msg: String)
    }

    private val navi = NaviHelper(context)
    private val listener = object : NaviHelperEventListener {
        override fun onDrivingInfo(info: DrivingInfo) {
            val d = info.destination ?: return
            events.onRemainSeconds(d.duration, d.name ?: "")
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { info.address?.let(events::onAddress) }
        override fun onDestinationArrived(info: DestinationArrivedInfo) = events.onArrived()
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

    fun requestLocation() = runCatching { navi.getCurrentLocationInfo() }.onFailure { events.onLog("위치 요청 실패: ${it.message}") }

    fun release() = runCatching { navi.removeListener(listener); navi.release() }
}
