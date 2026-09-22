package com.lyrebird.rc.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.SafetyLatchStore
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.server.ProcessCommandSurface
import com.lyrebird.rc.server.ProcessNetworkRuntimeRegistry
import com.lyrebird.rc.server.ProcessObstacleGuard
import com.lyrebird.rc.server.ProcessPayloadCommands
import com.lyrebird.rc.settings.LyrebirdSettings
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager

internal object ProcessTelemetryRuntimeRegistry : TelemetryFeed {
    private val aircraftTelemetry = V5AircraftTelemetrySource()
    private val telemetryCoordinator = TelemetryCoordinator()
    private val projection = V5AircraftTelemetryProjection(aircraftTelemetry, telemetryCoordinator)
    private var deviceStatusSource: DeviceStatusSource? = null
    private var connectionListenerStarted = false
    private var serialListenerStarted = false
    private var nameListenerStarted = false
    private var namePrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var flightStateSubscription: AutoCloseable? = null
    private var projectionSubscription: AutoCloseable? = null
    private var deviceStatusStarted = false

    @Volatile private var preferences: SharedPreferences? = null

    @Volatile private var aircraftSerial: String = "UNKNOWN"

    @Synchronized
    fun attach(context: Context) {
        val appContext = context.applicationContext
        preferences = appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)
        if (deviceStatusSource == null) {
            deviceStatusSource = DeviceStatusSource(appContext)
        }
        if (!deviceStatusStarted) {
            // The phone half of the frame — the RC's battery, its position, the Wi-Fi it is on —
            // is served by the process, on the same terms as the aircraft half: a ground station
            // reading telemetry from a closed RC must not be shown values frozen at the moment
            // its screen went away. Location updates refuse honestly without the permission, and
            // a screen is what asks for it (see the Flight Deck's startLocationUpdates).
            deviceStatusStarted = true
            deviceStatusSource?.let { source ->
                source.onChanged = { snapshot ->
                    snapshot.applyTo(telemetryCoordinator)
                    telemetryCoordinator.rebuildTelemetryCache()
                }
                source.startLocationUpdates()
                source.startSensorUpdates()
            }
        }
        if (projectionSubscription == null) {
            // The runtime's own projection into the telemetry frame. Subscribed for the life of
            // the process: the TCP stream keeps moving with no screen attached, instead of
            // freezing on the last frame a screen built.
            projectionSubscription = aircraftTelemetry.subscribe(projection)
            ProcessNetworkRuntimeRegistry.attachTelemetryFeed(this)
        }
        if (!nameListenerStarted) {
            nameListenerStarted = true
            // The name and the serial are identities the process publishes: discovery answers
            // with the name, the WHIP publisher publishes under it, the TCP frame reports it and
            // the flight-log files are named from it. They used to be written only by the
            // Flight Deck screen, so an RC with no screen introduced itself as "drone_1" over
            // TCP and wrote "..._unknown.jsonl" logs; this listener applies them from
            // preferences instead, and the operator's rename lands here the same way wherever it
            // came from. The listener is held in a field because SharedPreferences keeps
            // listeners weakly.
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == LyrebirdSettings.PREF_DRONE_NAME || key == LyrebirdSettings.PREF_STREAMING_MODE) {
                        applyProcessPublishedSettings()
                    }
                }
            namePrefListener = listener
            preferences?.registerOnSharedPreferenceChangeListener(listener)
            applyProcessPublishedSettings()
        }
        if (!serialListenerStarted) {
            serialListenerStarted = true
            attachSerialLatch(appContext)
        }
        if (flightStateSubscription == null) {
            // The runtime's own flight-state consumer, subscribed for the life of the process:
            // takeoff/landing effects and the controller's airborne latch stay in step with no
            // screen attached. Screens subscribe separately and close only their own handles.
            flightStateSubscription =
                aircraftTelemetry.subscribe(
                    FlightStateConsumer(
                        effects = V5FlightStateEffects(appContext),
                        scheduler = MainHandlerFlightStateScheduler(),
                    ),
                )
        }
        if (!connectionListenerStarted) {
            val connectionKey = FlightControllerKey.KeyConnection.create()
            KeyManager.getInstance().listen(connectionKey, this) { _, connected ->
                aircraftTelemetry.setConnectionState(connected == true)
                if (connected == true) {
                    // An aircraft that just came up is the only moment these can be read: the
                    // limits are not pushed on their own and the cache holds -1 until something
                    // asks (see DroneController.refreshFlightLimits).
                    DroneController.refreshFlightLimits()
                    // The thermal radiometric pipeline (temperature data + region metering) is a
                    // one-shot setup the camera forgets across a link drop, so it is armed from
                    // the process rather than by a screen that may not exist.
                    ProcessPayloadCommands.warmThermalPipeline()
                    // The obstacle sensors are only reachable while the link is up, so the guard
                    // re-arms here too: it keeps watching over a mission a ground station
                    // started with no screen open (see ProcessObstacleGuard).
                    ProcessObstacleGuard.armIfEnabled()
                } else {
                    ProcessPayloadCommands.resetThermalPipeline()
                }
            }
            aircraftTelemetry.setConnectionState(connectionKey.get(false))
            connectionListenerStarted = true
        }
    }

    fun aircraftTelemetry(): V5AircraftTelemetrySource = aircraftTelemetry

    fun telemetryCoordinator(): TelemetryCoordinator = telemetryCoordinator

    fun projection(): V5AircraftTelemetryProjection = projection

    fun deviceStatusSource(): DeviceStatusSource = deviceStatusSource ?: error("Process telemetry runtime is not attached")

    /**
     * The latch is per airframe, and the serial only exists after the SDK answers: attach the
     * persistence first with a provider that reads whatever serial is current, then restore the
     * latch for it. Running here — not in the screen — is what keeps Safety authority in force
     * across a UI teardown, and the read is deliberately repeated per restore (see
     * [ControlAuthority.restoreLatch]).
     */
    private fun attachSerialLatch(appContext: Context) {
        ControlAuthority.attachPersistence(
            SafetyLatchStore(appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)),
            aircraftSerial = { aircraftSerial },
        )
        val serialKey = KeyTools.createKey(FlightControllerKey.KeySerialNumber)
        KeyManager.getInstance().listen(serialKey, this) { _, value -> setAircraftSerial(value) }
        setAircraftSerial(serialKey.get(""))
    }

    private fun setAircraftSerial(value: String?) {
        aircraftSerial = value?.trim()?.takeIf { it.isNotEmpty() } ?: "UNKNOWN"
        // The serial is what an unnamed aircraft's name is derived from, and this is the moment it
        // appears — including long after startup, on a device that booted with its aircraft off.
        // The name keys discovery, the WHIP path and the dashboard's WHEP URL, so it is derived
        // here, in the process, with or without a screen (see LyrebirdSettings.applyAutomaticDroneName).
        if (preferences?.let { prefs -> LyrebirdSettings(prefs).applyAutomaticDroneName(aircraftSerial) } != null) {
            ProcessCommandSurface.onDroneNameChanged()
        }
        applyProcessPublishedSettings()
        runCatching { ControlAuthority.restoreLatch() }
    }

    /**
     * Apply the settings the process publishes on its own surfaces.
     *
     * The TCP telemetry frame and the flight-log session are process-owned; the name in the frame
     * and the name/serial in the log used to be written only by the screen that happened to be
     * open, so telemetry fell back to the coordinator's "drone_1" default and the logs to
     * "unknown" with no screen attached. The video mode's outbound name belongs here for the same
     * reason: it is part of the frame, and it has to be right with no screen open - a ground
     * station reading the frame and GET /config must not be told two different video paths.
     */
    private fun applyProcessPublishedSettings() {
        val prefs = preferences ?: return
        val settings = LyrebirdSettings(prefs)
        val name = settings.droneName(aircraftSerial)
        telemetryCoordinator.droneName = name
        telemetryCoordinator.streamingMode = settings.getStreamingMode().wireName
        LyrebirdFlightLogger.setDroneName(name)
        LyrebirdFlightLogger.setVehicleSerial(aircraftSerial)
    }

    override val droneSerial: String get() = aircraftSerial

    override fun telemetryJson(): String = telemetryCoordinator.getTelemetryJson()

    override fun gapTelemetryJson(): String = telemetryCoordinator.getGapTelemetryJson()
}

/** Schedules the landing grace on the main looper, where the old screen-side timer lived. */
private class MainHandlerFlightStateScheduler : FlightStateScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): ScheduledAction {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return ScheduledAction { handler.removeCallbacks(runnable) }
    }
}
