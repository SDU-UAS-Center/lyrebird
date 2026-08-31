package com.lyrebird.rc.telemetry

/**
 * Thread-safe telemetry coordinator.
 * Manages the caching and formatting of real-time telemetry data.
 * Stay decoupled from DJI SDK class dependencies by holding SDK variables as [Any?].
 */
class TelemetryCoordinator {
    @Volatile var isMockEnabled: Boolean = false
    @Volatile var mockSnapshot: MockTelemetrySnapshot? = null
    @Volatile var droneName: String = "drone_1"
    
    // Position & Attitude
    @Volatile var speed: Any? = null
    @Volatile var heading: Double = 0.0
    @Volatile var attitude: Any? = null
    @Volatile var location: Any? = null
    @Volatile var altitudeASL: Double = 0.0
    @Volatile var altitudeAGL: Double = 0.0
    @Volatile var gimbalAttitude: Any? = null
    @Volatile var gimbalJointAttitude: Any? = null
    
    // Battery & Satellites
    @Volatile var batteryLevel: Int = -1
    @Volatile var satelliteCount: Int = -1
    
    // Flight & Mission
    @Volatile var homeLocation: Any? = null
    @Volatile var distanceToHome: Double = 0.0
    @Volatile var waypointReached: Boolean = false
    @Volatile var intermediaryWaypointReached: Boolean = false
    @Volatile var yawReached: Boolean = false
    @Volatile var altitudeReached: Boolean = false
    @Volatile var isRecording: Boolean = false
    @Volatile var homeSet: Boolean = false
    @Volatile var flightMode: String = "UNKNOWN"
    @Volatile var isManualOverrideActive: Boolean = false

    // Command sequence ids: which command the matching *Reached flag refers to. The ground
    // station compares these against the seq returned when it issued the command, so a stale
    // latch from a previous command is not mistaken for the current one.
    @Volatile var waypointSeq: Long = 0
    @Volatile var yawSeq: Long = 0
    @Volatile var altitudeSeq: Long = 0

    // Take-off readiness, derived aircraft-side from the DJI system-status banner.
    @Volatile var readyToTakeoff: Boolean = false
    @Volatile var takeoffBlockReason: String = "UNKNOWN"

    // Last laser-rangefinder target fix, or null when the LRF has not locked a target.
    @Volatile var lrfTarget: Any? = null
    
    // Camera Zoom
    @Volatile var zoomFl: Int = -1
    @Volatile var hybridFl: Int = -1
    @Volatile var opticalFl: Int = -1
    @Volatile var zoomRatio: Double = 1.0
    
    // Battery assessment info
    @Volatile var remainingFlightTime: Int = 0
    @Volatile var timeNeededToGoHome: Int = 0
    @Volatile var timeNeededToLand: Int = 0
    @Volatile var totalTime: Int = 0
    @Volatile var maxRadiusCanFlyAndGoHome: Int = 0
    @Volatile var remainingCharge: Int = 0
    @Volatile var batteryNeededToLand: Int = 0
    @Volatile var batteryNeededToGoHome: Int = 0
    @Volatile var seriousLowBatteryThreshold: Int = 0
    @Volatile var lowBatteryThreshold: Int = 0
    
    // Phone location & status
    @Volatile var phoneLatitude: Double = 0.0
    @Volatile var phoneLongitude: Double = 0.0
    @Volatile var phoneHeading: Double = 0.0
    @Volatile var phonePressure: Float = 0.0f
    @Volatile var phoneBattery: Int = -1
    @Volatile var wifiRssi: Int = -100
    
    // WebRTC Metrics
    @Volatile var webRtcMetricsJson: String = "{}"
    
    // Detections status
    @Volatile var isDetectionsEnabled: Boolean = false
    @Volatile var isAutoSensingActive: Boolean = false
    @Volatile var edgeDetectionActive: Boolean = false
    @Volatile var detectionSource: String = "none"
    @Volatile var selectedDetectionSource: String = "none"
    @Volatile var detectionMenuLabel: String = "None"
    @Volatile var edgeModelName: String? = null
    @Volatile var edgeLabelsName: String? = null
    @Volatile var edgeConfidenceThreshold: Float? = null
    @Volatile var detectedTargetsJson: String = "[]"
    @Volatile var detectedTargetsSize: Int = 0

    // Streaming Config
    @Volatile var streamingMode: String = "webrtc"
    @Volatile var rtspPort: Int = 8554
    @Volatile var rtspUser: String = ""
    @Volatile var rtspPwd: String = ""
    @Volatile var rtmpUrl: String = ""
    @Volatile var consumptionPath: String = ""

    @Volatile private var cachedTelemetryJson: String = "{}"
    @Volatile private var cachedGapTelemetryJson: String = "{}"

    fun getTelemetryJson(): String = cachedTelemetryJson

    /** See [buildGapTelemetryJson]. */
    fun getGapTelemetryJson(): String = cachedGapTelemetryJson

    fun rebuildTelemetryCache() {
        cachedTelemetryJson = buildTelemetryJson()
        cachedGapTelemetryJson = buildGapTelemetryJson()
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun detectionTelemetryJson(): String {
        val thresholdJson = edgeConfidenceThreshold?.toString() ?: "null"
        val modelJson = edgeModelName?.let { "\"${escapeJson(it)}\"" } ?: "null"
        val labelsJson = edgeLabelsName?.let { "\"${escapeJson(it)}\"" } ?: "null"
        val active = when (detectionSource) {
            "none" -> false
            "dji_onboard" -> isAutoSensingActive
            "yolo_on_phone" -> edgeDetectionActive
            else -> false
        }
        return """{"source":"$detectionSource","selectedSource":"$selectedDetectionSource","label":"$detectionMenuLabel","enabled":$isDetectionsEnabled,"active":$active,"count":$detectedTargetsSize,"model":$modelJson,"labels":$labelsJson,"confidenceThreshold":$thresholdJson,"targets":$detectedTargetsJson}"""
    }

    fun streamingTelemetryJson(): String {
        return """{"mode":"$streamingMode","rtspPort":$rtspPort,"rtspUser":"${escapeJson(rtspUser)}","rtspPwd":"${escapeJson(rtspPwd)}","rtmpUrl":"${escapeJson(rtmpUrl)}","consumptionPath":"${escapeJson(consumptionPath)}"}"""
    }

    fun buildTelemetryJson(): String {
        val mock = mockSnapshot
        val streamingJson = streamingTelemetryJson()
        if (isMockEnabled && mock != null) {
            val phoneLocationJson = """{"latitude":$phoneLatitude,"longitude":$phoneLongitude,"heading":$phoneHeading,"pressure":$phonePressure,"battery":$phoneBattery,"wifiRssi":$wifiRssi}"""
            val detectionsJson = detectionTelemetryJson()

            return """{"droneName":"$droneName","speed":${mock.velocity},"heading":${mock.heading},"attitude":${mock.attitude},"location":${mock.location},"altitude":${mock.altitudeAGL},"lrfTarget":null,"phoneLocation":$phoneLocationJson,"webRtc":$webRtcMetricsJson,"detections":$detectionsJson,"streaming":$streamingJson,"gimbalAttitude":${mock.gimbalAttitude},"gimbalJointAttitude":${mock.gimbalAttitude},"zoomFl":24,"hybridFl":24,"opticalFl":24,"zoomRatio":1.0,"batteryLevel":${mock.batteryPercent},"satelliteCount":${mock.satelliteCount},"homeLocation":{"latitude":${mock.locationLatitude},"longitude":${mock.locationLongitude}},"distanceToHome":0.0,"waypointReached":false,"waypointSeq":0,"intermediaryWaypointReached":false,"yawReached":true,"yawSeq":0,"altitudeReached":true,"altitudeSeq":0,"isRecording":true,"homeSet":true,"remainingFlightTime":1320,"timeNeededToGoHome":45,"timeNeededToLand":18,"totalTime":63,"maxRadiusCanFlyAndGoHome":900,"remainingCharge":${mock.batteryPercent},"batteryNeededToLand":12,"batteryNeededToGoHome":18,"seriousLowBatteryThreshold":10,"lowBatteryThreshold":20,"flightMode":"${mock.flightMode}","readyToTakeoff":false,"takeoffBlockReason":"MOCK_IN_FLIGHT","isManualOverrideActive":false,"autoSensingActive":$isAutoSensingActive,"detectedTargets":$detectedTargetsJson}"""
        }

        val phoneLocationJson = """{"latitude":$phoneLatitude,"longitude":$phoneLongitude,"heading":$phoneHeading,"pressure":$phonePressure,"battery":$phoneBattery,"wifiRssi":$wifiRssi}"""
        val detectionsJson = detectionTelemetryJson()

        return """{"droneName":"$droneName","speed":$speed,"heading":$heading,"attitude":$attitude,"location":$location,"altitude":$altitudeAGL,"lrfTarget":${lrfTarget?.toString() ?: "null"},"phoneLocation":$phoneLocationJson,"webRtc":$webRtcMetricsJson,"detections":$detectionsJson,"streaming":$streamingJson,"gimbalAttitude":$gimbalAttitude,"gimbalJointAttitude":$gimbalJointAttitude,"zoomFl":$zoomFl,"hybridFl":$hybridFl,"opticalFl":$opticalFl,"zoomRatio":$zoomRatio,"batteryLevel":$batteryLevel,"satelliteCount":$satelliteCount,"homeLocation":$homeLocation,"distanceToHome":$distanceToHome,"waypointReached":$waypointReached,"waypointSeq":$waypointSeq,"intermediaryWaypointReached":$intermediaryWaypointReached,"yawReached":$yawReached,"yawSeq":$yawSeq,"altitudeReached":$altitudeReached,"altitudeSeq":$altitudeSeq,"isRecording":$isRecording,"homeSet":$homeSet,"remainingFlightTime":$remainingFlightTime,"timeNeededToGoHome":$timeNeededToGoHome,"timeNeededToLand":$timeNeededToLand,"totalTime":$totalTime,"maxRadiusCanFlyAndGoHome":$maxRadiusCanFlyAndGoHome,"remainingCharge":$remainingCharge,"batteryNeededToLand":$batteryNeededToLand,"batteryNeededToGoHome":$batteryNeededToGoHome,"seriousLowBatteryThreshold":$seriousLowBatteryThreshold,"lowBatteryThreshold":$lowBatteryThreshold,"flightMode":"$flightMode","readyToTakeoff":$readyToTakeoff,"takeoffBlockReason":"$takeoffBlockReason","isManualOverrideActive":$isManualOverrideActive,"autoSensingActive":$isAutoSensingActive,"detectedTargets":$detectedTargetsJson}"""
    }

    /**
     * Telemetry for a ground station that already reads MAVLink: only the fields MAVLink has no
     * form for at all (a DJI phone's own GPS/heading/pressure/battery/Wi-Fi, WebRTC stats,
     * streaming config, and a handful of numbers — zoom ratio, distance to home, remaining
     * charge, the low-battery thresholds, the intermediary-waypoint flag — that never made it
     * into a MAVLink message). Everything MAVLink already carries (location, attitude, battery
     * percent, gimbal, flight mode, waypoint-reached latches, ...) is left out on purpose: sending
     * it again over TCP would just be the same state twice on two wires to the same listener.
     * `detections` rides along whole rather than split further — it changes rarely and is small,
     * so splitting it for a partial win is not worth the fragility.
     *
     * Carries `"telemetryMode":"gap"` so a receiver can tell a trimmed object from a full one on
     * sight and merge rather than replace its cached state — see the ground-station client's
     * `_process_telemetry_data`, which relies on this marker rather than on its own transport
     * setting, so it stays correct even against an aircraft build that predates gap mode and
     * always sends full, unmarked objects regardless of what the client asked for.
     */
    fun buildGapTelemetryJson(): String {
        val phoneLocationJson = """{"latitude":$phoneLatitude,"longitude":$phoneLongitude,"heading":$phoneHeading,"pressure":$phonePressure,"battery":$phoneBattery,"wifiRssi":$wifiRssi}"""
        val detectionsJson = detectionTelemetryJson()
        val streamingJson = streamingTelemetryJson()

        return """{"telemetryMode":"gap","droneName":"$droneName","phoneLocation":$phoneLocationJson,"webRtc":$webRtcMetricsJson,"detections":$detectionsJson,"streaming":$streamingJson,"zoomRatio":$zoomRatio,"distanceToHome":$distanceToHome,"intermediaryWaypointReached":$intermediaryWaypointReached,"remainingCharge":$remainingCharge,"seriousLowBatteryThreshold":$seriousLowBatteryThreshold,"lowBatteryThreshold":$lowBatteryThreshold}"""
    }
}
