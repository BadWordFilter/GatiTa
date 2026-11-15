package ai.gatita.app

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener

/** Pleos Vehicle SDK 래퍼: 기어, 도어, 창문, 엠비언트 조명. 실패는 로그로 남기고 앱은 계속 동작합니다. */
class VehicleBridge(context: Context, private val events: Events) {

    interface Events {
        fun onGear(parked: Boolean, label: String)
        fun onDoor(door: DoorValue, open: Boolean)
        fun onRearWindow(position: Int)
        /** 반려동물 보호 중 창문이 환기 높이 이상 열려 되돌렸을 때 */
        fun onRearWindowClamped()
        fun onLog(msg: String)
    }

    private val vehicle = Vehicle(context)
    private val doors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    // 차종마다 지원 영역이 달라서, 시작할 때 capability API로 실제 지원 영역을 조회해 씁니다.
    @Volatile private var ambientAreas: List<SeatArea> = emptyList()
    @Volatile private var rearWindows: List<WindowArea> = emptyList()
    @Volatile private var pendingAmbient: Int? = null
    // 창문 위치 값의 범위(차종마다 다름, 에뮬레이터는 0~10). capability 조회 결과로 채웁니다.
    @Volatile private var windowMin = 0
    @Volatile private var windowMax = 10
    /** true면 뒷창이 환기 높이보다 더 열릴 때 그 창만 환기 높이로 되돌립니다 (반려동물 보호) */
    @Volatile var guardRearWindows = false
    /** 환기 높이: 범위의 20% (0~10이면 2). 닫힌 창을 이 값으로 여는 일은 없습니다 */
    val ventPosition get() = windowMin + Math.ceil((windowMax - windowMin) * VENT_RATIO).toInt()

    fun start() {
        safe("initialize") { vehicle.initialize() }
        safe("gear") {
            val gear = vehicle.getGear()
            gear.getCurrentGearSelectionState({ g -> emitGear(g) }, { e -> fail("gear get", e) })
            gear.registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = emitGear(gear)
                override fun onFailed(e: Exception) = fail("gear listen", e)
            })
        }
        safe("door") {
            val door = vehicle.getDoor()
            doors.forEach { value ->
                door.registerDoorPosition(DoorArea(value), object : DoorPositionListener {
                    override fun onPositionUpdated(position: Int?) = events.onDoor(value, (position ?: 0) > 0)
                    override fun onFailed(e: Exception) = fail("door $value", e)
                })
            }
        }
        safe("ambient caps") {
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ _, areas ->
                ambientAreas = areas
                events.onLog("엠비언트 지원 영역 ${areas.size}개")
                pendingAmbient?.let { setAmbient(it) }
            }, { e -> fail("ambient caps", e) })
        }
        safe("window") {
            val window = vehicle.getWindow()
            window.checkWindowPositionCapabilities({ _, map ->
                rearWindows = map.keys.filter { a -> a.values.any { it == WindowValue.ROW_2_LEFT || it == WindowValue.ROW_2_RIGHT } }
                rearWindows.firstOrNull()?.let { a -> map[a]?.let { b -> windowMin = b.minValue; windowMax = b.maxValue } }
                events.onLog("뒷좌석 창문 영역 ${rearWindows.size}개, 범위 $windowMin~$windowMax, 환기 높이 $ventPosition")
                rearWindows.forEach { area ->
                    window.registerWindowPosition(area, object : WindowPositionListener {
                        override fun onWindowPositionUpdated(position: Int?) {
                            val pos = position ?: 0
                            events.onRearWindow(pos)
                            if (guardRearWindows && pos > ventPosition) {
                                // 더 열린 그 창만 닫는 방향으로 되돌림
                                window.setWindowPosition(area, ventPosition, { _, _ -> }, { e -> fail("window set", e) })
                                events.onRearWindowClamped()
                            }
                        }
                        override fun onFailed(e: Exception) = fail("window", e)
                    })
                }
            }, { e -> fail("window caps", e) })
        }
    }

    /** 엠비언트 조명 색 변경 (0xRRGGBB) */
    fun setAmbient(rgb: Int) = safe("ambient") {
        pendingAmbient = rgb
        ambientAreas.forEach { area ->
            vehicle.getLight().setAmbientLightBrightnessColor(area, rgb and 0xFFFFFF, { _, _ -> }, { e -> fail("ambient set", e) })
        }
    }


    fun release() = safe("release") { vehicle.release() }

    private fun emitGear(g: VehicleGear?) {
        val parked = g == VehicleGear.GEAR_PARK || g == VehicleGear.GEAR_NEUTRAL || g == null || g == VehicleGear.GEAR_UNKNOWN
        val label = when (g) {
            VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"
            null, VehicleGear.GEAR_UNKNOWN -> "-"; else -> "D"
        }
        events.onGear(parked, label)
    }

    private fun fail(what: String, e: Exception?) = events.onLog("Vehicle $what 실패: ${e?.message}")

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, what, t); events.onLog("Vehicle $what 오류: ${t.message}") }
    }

    companion object {
        private const val TAG = "GatiTa"
        private const val VENT_RATIO = 0.2
    }
}
