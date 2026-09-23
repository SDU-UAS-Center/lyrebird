package com.lyrebird.rc.perception

import android.os.SystemClock
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener

/**
 * [ObstacleSensorPort] over DJI's perception manager.
 *
 * The SDK-shaped handling lives here: the listener registration, the sweep timestamp, and the
 * conversion from the SDK's millimetre/interval payload — wrapped, because the SDK unloads
 * obstacle payloads of shapes it does not document, and a malformed sweep must read as "no data"
 * rather than as a crash on DJI's callback thread.
 */
internal class V5ObstacleSensorPort : ObstacleSensorPort {
    private var listener: ObstacleDataListener? = null

    override fun start(onSweep: (ObstacleReading) -> Unit) {
        val sweepListener =
            ObstacleDataListener { data ->
                val reading = data.toReading() ?: return@ObstacleDataListener
                onSweep(reading)
            }
        listener = sweepListener
        PerceptionManager.getInstance().addObstacleDataListener(sweepListener)
    }

    override fun stop() {
        listener?.let { PerceptionManager.getInstance().removeObstacleDataListener(it) }
        listener = null
    }

    private fun ObstacleData?.toReading(): ObstacleReading? {
        val data = this ?: return null
        return runCatching {
            ObstacleReading.fromMillimetres(
                horizontalMm = data.horizontalObstacleDistance.orEmpty(),
                angleIntervalDeg = data.horizontalAngleInterval.toDouble(),
                upwardMm = data.upwardObstacleDistance,
                downwardMm = data.downwardObstacleDistance,
                timestampMs = SystemClock.elapsedRealtime(),
            )
        }.getOrNull()
    }
}
