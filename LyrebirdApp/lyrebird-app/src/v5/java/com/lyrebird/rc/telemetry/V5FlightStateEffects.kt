package com.lyrebird.rc.telemetry

import android.content.Context
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.settings.DetectionSource
import com.lyrebird.rc.settings.LyrebirdSettings
import java.io.File

/**
 * The V5 side of the flight-state effects.
 *
 * Runs from the process runtime's telemetry subscription, so the flight-log session and the
 * controller's airborne latch are no longer tied to a screen being open — this is what makes a
 * takeoff that happens with no UI attached still open a log and arm onboard detection.
 */
internal class V5FlightStateEffects(
    private val context: Context,
) : FlightStateEffects {
    private val settings by lazy {
        LyrebirdSettings(context.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE))
    }

    override fun isReturningHome(): Boolean = DroneController.droneStatus == DroneController.DroneStatus.RETURNING_HOME

    override fun onAirborneChanged(flying: Boolean) {
        DroneController.isAirborne = flying
    }

    override fun onTakeoff() {
        LyrebirdFlightLogger.startSession()
        // Start AutoSensing on takeoff if DJI onboard detections are selected.
        if (settings.activeDetectionSource() == DetectionSource.DJI_ONBOARD &&
            !ProcessDetectionRuntimeRegistry.isAutoSensingActive()
        ) {
            ProcessDetectionRuntimeRegistry.startSelected()
        }
    }

    override fun onLanded() {
        LyrebirdFlightLogger.endSession("landed")
        syncDjiFlightLogs()
    }

    override fun onRemoteReturnToHome() {
        DroneController.activateManualOverride()
    }

    /** Copy DJI TXT records into the Lyrebird folder; idempotent, safe to repeat. */
    private fun syncDjiFlightLogs() {
        val root = context.getExternalFilesDir(null) ?: return
        Thread {
            runCatching {
                LyrebirdFlightLogger.syncDjiFlightLogs(File(root, "DJI/FlightRecord").absolutePath)
            }
        }.start()
    }
}
