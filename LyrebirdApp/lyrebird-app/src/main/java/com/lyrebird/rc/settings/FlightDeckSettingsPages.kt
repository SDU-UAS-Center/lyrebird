package com.lyrebird.rc.settings

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lyrebird.rc.R
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.util.NetworkUtils

internal class FlightDeckSettingsPages(
    context: Context,
    private val sharedPreferences: SharedPreferences,
    private val settings: LyrebirdSettings,
    private val settingsDialogViews: SettingsDialogViews,
    actions: SettingsPageActions,
) : ContextWrapper(context),
    SettingsPageActions by actions {
    companion object {
        private const val TAG = "LyrebirdDefaultLayout"
    }

    internal fun showDetectionSourceDialog() {
        val allSources = arrayOf(DetectionSource.DJI_ONBOARD, DetectionSource.YOLO_ON_PHONE)
        val labels =
            allSources
                .map { source ->
                    if (source == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
                        "${source.menuLabel} (connect drone)"
                    } else {
                        source.menuLabel
                    }
                }.toTypedArray()
        val checkedIndex = allSources.indexOf(settings.getDetectionSource()).coerceAtLeast(0)

        AlertDialog
            .Builder(this)
            .setTitle("Detection source")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                setDetectionSource(allSources[which])
                dialog.dismiss()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showDetectionSettingsDialog() {
        val modelName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_NAME, "Select...")
        val labelsName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_LABELS_NAME, "Default person")
        val confidence = (settings.getEdgeConfidenceThreshold() * 100).toInt()
        val rows =
            listOf(
                SettingsDialogViews.SettingsActionRow("Source", settings.getDetectionSource().menuLabel),
                SettingsDialogViews.SettingsActionRow("YOLO model", modelName),
                SettingsDialogViews.SettingsActionRow("YOLO labels", labelsName),
                SettingsDialogViews.SettingsActionRow("YOLO confidence", "$confidence%"),
            )

        AlertDialog
            .Builder(this)
            .setTitle("Detection Settings")
            .setAdapter(settingsDialogViews.actionRowAdapter(rows)) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showDetectionSourceDialog()
                    1 -> showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model")
                    2 -> showEdgeFilePicker(REQUEST_EDGE_LABELS_FILE, "Select model labels")
                    3 -> showEdgeConfidenceDialog()
                }
            }.setNegativeButton("Close", null)
            .show()
    }

    internal fun showLyrebirdSettingsMenu() {
        val sdCardStatus = getDroneStorageStatus(AircraftStorage.SDCARD, "SD card")
        val internalStatus = getDroneStorageStatus(AircraftStorage.INTERNAL, "Internal")
        val previousDialog = settingsDialogViews.lyrebirdSettingsDialog
        val dialog =
            Dialog(this, R.style.LyrebirdSettingsDialog).apply {
                setContentView(R.layout.dialog_lyrebird_settings_cockpit)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setOnDismissListener {
                    if (settingsDialogViews.lyrebirdSettingsDialog === this) settingsDialogViews.lyrebirdSettingsDialog = null
                }
            }
        settingsDialogViews.lyrebirdSettingsDialog = dialog

        dialog.findViewById<TextView>(R.id.text_settings_hint)?.text =
            getString(R.string.lyrebird_settings_hint, droneName)
        dialog.findViewById<TextView>(R.id.text_settings_summary)?.text =
            "${settings.getStreamingMode().menuLabel}  /  ${settings.getWebRTCFps()} fps  /  " +
            "${settings.getWebRTCResolutionPreset().menuLabel}  /  " +
            "MAVLink ${if (isMavlinkFlightAllowed()) "allowed" else "blocked"}"

        val cockpit = dialog.findViewById<LinearLayout>(R.id.settings_cockpit_content)
        val columns =
            listOf(
                LinearLayout(this),
                LinearLayout(this),
                LinearLayout(this),
                LinearLayout(this),
            )
        columns.forEachIndexed { index, column ->
            column.orientation = LinearLayout.VERTICAL
            column.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            val columnScroll =
                android.widget.ScrollView(this).apply {
                    isFillViewport = true
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                0,
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                1f,
                            ).apply {
                                if (index > 0) marginStart = settingsDialogViews.dpToPx(5)
                            }
                    addView(column)
                }
            cockpit?.addView(columnScroll)
        }

        val aircraftColumn = columns[0]
        settingsDialogViews.addCockpitSection(aircraftColumn, "AIRCRAFT")
        settingsDialogViews.addCockpitRow(aircraftColumn, "Drone name", droneName) { showBrandedDroneNamePage() }
        settingsDialogViews.addCockpitRow(aircraftColumn, "MAVLink vehicle ID", "V${currentMavlinkSystemId()}") {
            showBrandedMavlinkSystemIdPage()
        }
        val aircraft = settingsSnapshot()
        settingsDialogViews.addCockpitRow(aircraftColumn, "Detected aircraft", aircraft.detectedAircraft)
        settingsDialogViews.addCockpitRow(
            aircraftColumn,
            "Control profile",
            aircraft.controlProfile,
        )
        settingsDialogViews.addCockpitSection(aircraftColumn, "REMOTE CONTROLLER")
        settingsDialogViews.addCockpitRow(
            aircraftColumn,
            "RC stick mode",
            flight.getRcControlMode().uppercase(),
        ) { showBrandedRcControlModePage() }
        settingsDialogViews.addCockpitRow(aircraftColumn, "RC pairing", flight.getRcPairingStatus().replaceFirstChar { it.uppercase() }) {
            showBrandedRcPairingPage()
        }
        settingsDialogViews.addCockpitRow(aircraftColumn, "HD frequency", flight.getHdFrequencyBand())

        settingsDialogViews.addCockpitSection(aircraftColumn, "FLEET")
        val fleetDetail = SettingsDisplay.fleet(fleetPeerCount)
        settingsDialogViews.addCockpitRow(aircraftColumn, "Other aircraft", fleetDetail) {
            if (!showFleetDialog()) Toast.makeText(this, "Fleet awareness is off", Toast.LENGTH_SHORT).show()
        }
        settingsDialogViews.addCockpitRow(
            aircraftColumn,
            "MAVLink flight",
            if (isMavlinkFlightAllowed()) "Allowed" else "Blocked",
        ) { showBrandedMavlinkPage() }

        val videoColumn = columns[1]
        settingsDialogViews.addCockpitSection(videoColumn, "VIDEO / STREAM")
        settingsDialogViews.addCockpitRow(videoColumn, "Protocol", settings.getStreamingMode().menuLabel) {
            showBrandedStreamSettingsPage()
        }
        settingsDialogViews.addCockpitRow(videoColumn, "Resolution", settings.getWebRTCResolutionPreset().menuLabel) {
            showBrandedResolutionPage()
        }
        settingsDialogViews.addCockpitRow(videoColumn, "Frame rate", "${settings.getWebRTCFps()} fps") {
            showBrandedFpsPage()
        }
        settingsDialogViews.addCockpitRow(videoColumn, "WHIP server", settings.getMediamtxServer().ifEmpty { "Auto" }) {
            showBrandedMediamtxServerPage()
        }
        settingsDialogViews.addCockpitRow(
            videoColumn,
            "Surface H264",
            if (settings.isDjiSurfaceH264EncoderEnabled()) "Experimental / on" else "Default encoder",
        ) {
            toggleDjiSurfaceH264Encoder()
        }

        val flightColumn = columns[2]
        settingsDialogViews.addCockpitSection(flightColumn, "FLIGHT LIMITS")
        settingsDialogViews.addCockpitRow(flightColumn, "Obstacle guard", obstacleGuardSummary()) {
            toggleObstacleGuard()
        }
        settingsDialogViews.addCockpitRow(flightColumn, "RTH altitude", settingsDialogViews.formatCockpitLimit(flight.getRTHAltitude())) {
            showBrandedRthAltitudePage()
        }
        settingsDialogViews.addCockpitRow(
            flightColumn,
            "Max flight height",
            settingsDialogViews.formatCockpitLimit(flight.getMaxFlightHeight()),
        ) { showBrandedMaxFlightHeightPage() }
        settingsDialogViews.addCockpitRow(
            flightColumn,
            "Max distance from home",
            settingsDialogViews.formatCockpitLimit(flight.getMaxFlightDistance()),
        ) { showBrandedMaxFlightDistancePage() }
        settingsDialogViews.addCockpitRow(
            flightColumn,
            "Distance limit",
            if (flight.getDistanceLimitEnabled()) "Enabled" else "Disabled",
        ) {
            flight.setDistanceLimitEnabled(!flight.getDistanceLimitEnabled())
            showLyrebirdSettingsMenu()
        }

        val detectionColumn = columns[3]
        settingsDialogViews.addCockpitSection(detectionColumn, "DETECTION")
        settingsDialogViews.addCockpitRow(
            detectionColumn,
            "Detections",
            if (isDetectionActiveForUi()) "Enabled" else "Disabled",
        ) {
            setDetectionsEnabled(!isDetectionActiveForUi())
            showLyrebirdSettingsMenu()
        }
        settingsDialogViews.addCockpitRow(detectionColumn, "Detection source", settings.getDetectionSource().menuLabel) {
            showBrandedDetectionSettingsPage()
        }
        settingsDialogViews.addCockpitRow(
            detectionColumn,
            "Confidence",
            "${(settings.getEdgeConfidenceThreshold() * 100).toInt()}%",
        ) { showBrandedConfidencePage() }
        settingsDialogViews.addCockpitSection(detectionColumn, "STORAGE")
        settingsDialogViews.addCockpitStorageRow(detectionColumn, "SD card", sdCardStatus.summary, R.drawable.uxsdk_ic_sdcard) {
            showBrandedFormatStoragePage(AircraftStorage.SDCARD, "SD card")
        }
        settingsDialogViews.addCockpitStorageRow(detectionColumn, "Internal storage", internalStatus.summary, R.drawable.uxsdk_ic_emmc) {
            showBrandedFormatStoragePage(AircraftStorage.INTERNAL, "Internal storage")
        }

        dialog.findViewById<ImageButton>(R.id.button_settings_close)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<ImageButton>(R.id.button_settings_overflow)?.setOnClickListener { anchor ->
            showLyrebirdSettingsOverflow(anchor)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.window?.setWindowAnimations(0)
        previousDialog?.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        previousDialog?.dismiss()
    }

    internal fun showLyrebirdSettingsOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Change drone name")
            menu.add(0, 20, 1, "Stream / WebRTC settings")
            menu.add(0, 10, 2, "Detection settings")
            menu.add(0, 22, 3, mavlinkFlightAllowedMenuLabel())
            menu.add(0, 3, 4, "Format SD card")
            menu.add(0, 4, 5, "Format internal storage")
            setOnMenuItemClickListener { item ->
                openBrandedSettingsItem(item.itemId)
                true
            }
            show()
        }
    }

    internal fun openBrandedSettingsItem(itemId: Int) {
        when (itemId) {
            1 -> showBrandedDroneNamePage()
            20 -> showBrandedStreamSettingsPage()
            21 -> {
                setDetectionsEnabled(!isDetectionActiveForUi())
                showLyrebirdSettingsMenu()
            }
            10 -> showBrandedDetectionSettingsPage()
            22 -> showBrandedMavlinkPage()
            3 -> showBrandedFormatStoragePage(AircraftStorage.SDCARD, "SD card")
            4 -> showBrandedFormatStoragePage(AircraftStorage.INTERNAL, "Internal storage")
        }
    }

    internal fun showBrandedRcControlModePage() {
        val modes = listOf("jp", "usa", "ch", "custom")
        settingsDialogViews.showBrandedChoicePage(
            "RC STICK MODE",
            "Choose the stick mapping used by the remote controller",
            modes.map { it.uppercase() },
            modes.indexOf(flight.getRcControlMode()).coerceAtLeast(0),
            onSelected = { index -> flight.setRcControlMode(modes[index]) },
            returnPage = ::showLyrebirdSettingsMenu,
            onBack = ::showLyrebirdSettingsMenu,
        )
    }

    internal fun showBrandedRcPairingPage() {
        settingsDialogViews.showBrandedSettingsSubpage("RC PAIRING", "Link the remote controller to this aircraft") { container, _ ->
            settingsDialogViews.addBrandedSettingsSection(container, "CURRENT STATUS")
            settingsDialogViews.addBrandedSettingsRow(container, "Pairing status", flight.getRcPairingStatus())
            settingsDialogViews.addBrandedSettingsButton(container, "Start pairing", {
                flight.requestRcPairing()
                showBrandedRcPairingPage()
            })
            settingsDialogViews.addBrandedSettingsButton(container, "Stop pairing", {
                flight.stopRcPairing()
                showBrandedRcPairingPage()
            })
        }
    }

    internal fun showBrandedRthAltitudePage() {
        settingsDialogViews.showBrandedIntegerEditPage(
            "RTH ALTITUDE",
            "Height used when return-to-home is commanded",
            flight.getRTHAltitude(),
            "Altitude in metres",
            onSave = { flight.setRTHAltitude(it) },
        )
    }

    internal fun showBrandedMaxFlightHeightPage() {
        settingsDialogViews.showBrandedIntegerEditPage(
            "MAX FLIGHT HEIGHT",
            "Upper altitude limit reported by the aircraft",
            flight.getMaxFlightHeight(),
            "Height in metres",
            onSave = { flight.setMaxFlightHeight(it) },
        )
    }

    internal fun showBrandedMaxFlightDistancePage() {
        settingsDialogViews.showBrandedIntegerEditPage(
            "MAX DISTANCE FROM HOME",
            "Horizontal distance limit from the recorded home point",
            flight.getMaxFlightDistance(),
            "Distance in metres",
            onSave = { flight.setMaxFlightDistance(it) },
        )
    }

    internal fun showBrandedDroneNamePage() {
        showBrandedIdentitiesPage()
    }

    internal fun showBrandedMavlinkSystemIdPage() {
        showBrandedIdentitiesPage()
    }

/** Combined editor for the drone name and MAVLink vehicle ID, with a short help line for each. */
    internal fun showBrandedIdentitiesPage() {
        val configuredSysId =
            prefIntOrDefault(
                MavlinkEndpointConfig.PREF_SYSTEM_ID,
                MavlinkEndpointConfig.DEFAULT_SYSTEM_ID,
            )
        settingsDialogViews.showBrandedSettingsSubpage(
            "DRONE IDENTITY",
            getString(R.string.lyrebird_identity_subtitle),
            onBack = ::showLyrebirdSettingsMenu,
        ) { container, _ ->
            settingsDialogViews.addBrandedSettingsSection(container, "DRONE NAME")
            val nameInput =
                EditText(this).apply {
                    setText(droneName)
                    hint = "e.g. mini3, alpha, scout (blank = automatic)"
                    setSingleLine(true)
                    setTextColor(ContextCompat.getColor(this@FlightDeckSettingsPages, R.color.lyrebird_text))
                    setHintTextColor(ContextCompat.getColor(this@FlightDeckSettingsPages, R.color.lyrebird_muted))
                    setPadding(
                        settingsDialogViews.dpToPx(16),
                        settingsDialogViews.dpToPx(12),
                        settingsDialogViews.dpToPx(16),
                        settingsDialogViews.dpToPx(12),
                    )
                    setBackgroundResource(R.drawable.lyrebird_settings_row)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                settingsDialogViews.dpToPx(58),
                            ).apply { bottomMargin = settingsDialogViews.dpToPx(0) }
                }
            container.addView(nameInput)
            container.addView(settingsDialogViews.identityHelpText(getString(R.string.drone_name_help)))

            settingsDialogViews.addBrandedSettingsSection(container, "MAVLINK VEHICLE ID")
            val idInput =
                EditText(this).apply {
                    setText(if (MavlinkSystemId.isManual(configuredSysId)) configuredSysId.toString() else "")
                    hint = "0 = automatic, 1-99 = manual"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setTextColor(ContextCompat.getColor(this@FlightDeckSettingsPages, R.color.lyrebird_text))
                    setHintTextColor(ContextCompat.getColor(this@FlightDeckSettingsPages, R.color.lyrebird_muted))
                    setPadding(
                        settingsDialogViews.dpToPx(16),
                        settingsDialogViews.dpToPx(12),
                        settingsDialogViews.dpToPx(16),
                        settingsDialogViews.dpToPx(12),
                    )
                    setBackgroundResource(R.drawable.lyrebird_settings_row)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                settingsDialogViews.dpToPx(58),
                            ).apply { bottomMargin = settingsDialogViews.dpToPx(0) }
                }
            container.addView(idInput)
            container.addView(settingsDialogViews.identityHelpText(getString(R.string.vehicle_id_help)))

            settingsDialogViews.addBrandedSettingsButton(container, "Save", {
                var valid = true
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    setAutomaticDroneName()
                } else if (!setDroneName(name)) {
                    valid = false
                    Toast.makeText(this, "Drone name must be 1-32 characters", Toast.LENGTH_SHORT).show()
                }
                val idText = idInput.text.toString().trim()
                val id = if (idText.isBlank()) MavlinkSystemId.AUTO else idText.toIntOrNull()
                if (id == null || (id != MavlinkSystemId.AUTO && !MavlinkSystemId.isManual(id))) {
                    valid = false
                    Toast.makeText(this, "Enter 0 for automatic, or 1-99 for a manual ID", Toast.LENGTH_SHORT).show()
                } else {
                    setMavlinkSystemId(id)
                }
                if (valid) showLyrebirdSettingsMenu()
            })
        }
    }

    internal fun showBrandedStreamSettingsPage() {
        val mode = settings.getStreamingMode()
        settingsDialogViews.showBrandedSettingsSubpage("STREAM / WEBRTC", "Media path and sender configuration") { container, _ ->
            settingsDialogViews.addBrandedSettingsSection(container, "PROTOCOL")
            settingsDialogViews.addBrandedSettingsRow(container, "Streaming protocol", mode.menuLabel, R.drawable.uxsdk_ic_setting_hd) {
                showBrandedStreamingModePage()
            }
            when (mode) {
                StreamingMode.WEBRTC -> {
                    settingsDialogViews.addBrandedSettingsSection(container, "WEBRTC")
                    val server = sharedPreferences.getString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, "")?.trim().orEmpty()
                    settingsDialogViews.addBrandedSettingsRow(container, "WHIP server", server.ifEmpty { "Auto" }) {
                        showBrandedMediamtxServerPage()
                    }
                    settingsDialogViews.addBrandedSettingsRow(container, "Frame rate", "${settings.getWebRTCFps()} fps") {
                        showBrandedFpsPage()
                    }
                    settingsDialogViews.addBrandedSettingsRow(container, "Resolution", settings.getWebRTCResolutionPreset().menuLabel) {
                        showBrandedResolutionPage()
                    }
                    settingsDialogViews.addBrandedSettingsRow(
                        container,
                        "Surface H264 encoder",
                        if (settings.isDjiSurfaceH264EncoderEnabled()) "Experimental / enabled" else "Default WebRTC encoder",
                    ) {
                        toggleDjiSurfaceH264Encoder()
                    }
                }
                StreamingMode.RTMP ->
                    settingsDialogViews.addBrandedSettingsRow(
                        container,
                        "RTMP server",
                        getRtmpUrl(NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"),
                    ) {
                        showRtmpConfigDialog()
                    }
                StreamingMode.RTSP ->
                    settingsDialogViews.addBrandedSettingsRow(container, "RTSP configuration", "Port ${settings.getRtspPort()}") {
                        showRtspConfigDialog()
                    }
                StreamingMode.AGORA ->
                    settingsDialogViews.addBrandedSettingsRow(
                        container,
                        "Agora configuration",
                        settings.getAgoraChannel().ifEmpty { "None" },
                    ) {
                        showAgoraConfigDialog()
                    }
                StreamingMode.GB28181 ->
                    settingsDialogViews.addBrandedSettingsRow(
                        container,
                        "GB28181 configuration",
                        settings.getGbServerIp().ifEmpty { "None" },
                    ) {
                        showGb28181ConfigDialog()
                    }
            }
        }
    }

    internal fun showBrandedStreamingModePage() {
        val modes = StreamingMode.entries.toList()
        settingsDialogViews.showBrandedChoicePage(
            "STREAMING PROTOCOL",
            "Choose the transport used by this aircraft",
            modes.map { it.menuLabel },
            modes.indexOf(settings.getStreamingMode()).coerceAtLeast(0),
            onSelected = { index ->
                setStreamingMode(modes[index])
                if (shouldRestartActiveStreaming()) restartActiveStreaming()
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage,
        )
    }

    internal fun showBrandedMediamtxServerPage() {
        settingsDialogViews.showBrandedEditPage(
            "WHIP SERVER",
            "Leave blank to use the first telemetry client address",
            sharedPreferences.getString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, "").orEmpty(),
            "host or host:port",
            onSave = { value ->
                sharedPreferences.edit().putString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, value).apply()
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage,
        )
    }

    internal fun showBrandedFpsPage() {
        settingsDialogViews.showBrandedChoicePage(
            "WEBRTC FRAME RATE",
            "The requested sender cadence",
            LyrebirdSettings.WEBRTC_FPS_OPTIONS.map { "$it fps" },
            LyrebirdSettings.WEBRTC_FPS_OPTIONS.indexOf(settings.getWebRTCFps()).coerceAtLeast(0),
            onSelected = { index ->
                val fps = LyrebirdSettings.WEBRTC_FPS_OPTIONS[index]
                sharedPreferences.edit().putInt(LyrebirdSettings.PREF_WEBRTC_FPS, fps).apply()
                changeVideoOptions()
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage,
        )
    }

    internal fun showBrandedResolutionPage() {
        val presets = StreamResolutionPreset.entries.toList()
        settingsDialogViews.showBrandedChoicePage(
            "WEBRTC RESOLUTION",
            "Choose the sender output size",
            presets.map { if (it.width > 0 && it.height > 0) "${it.menuLabel} (${it.width}x${it.height})" else it.menuLabel },
            presets.indexOf(settings.getWebRTCResolutionPreset()).coerceAtLeast(0),
            onSelected = { index ->
                sharedPreferences.edit().putString(LyrebirdSettings.PREF_WEBRTC_RESOLUTION, presets[index].prefValue).apply()
                changeVideoOptions()
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage,
        )
    }

    internal fun showBrandedDetectionSettingsPage() {
        settingsDialogViews.showBrandedSettingsSubpage("DETECTION", "On-device and aircraft perception controls") { container, _ ->
            settingsDialogViews.addBrandedSettingsSection(container, "SOURCE")
            settingsDialogViews.addBrandedSettingsRow(
                container,
                "Detection source",
                settings.getDetectionSource().menuLabel,
                R.drawable.uxsdk_ic_vision_sensors,
            ) {
                showBrandedDetectionSourcePage()
            }
            settingsDialogViews.addBrandedSettingsRow(
                container,
                "YOLO model",
                sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_NAME, "Select...").orEmpty(),
            ) {
                showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model")
            }
            settingsDialogViews.addBrandedSettingsRow(
                container,
                "YOLO labels",
                sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_LABELS_NAME, "Default person").orEmpty(),
            ) {
                showEdgeFilePicker(REQUEST_EDGE_LABELS_FILE, "Select model labels")
            }
            settingsDialogViews.addBrandedSettingsRow(
                container,
                "Confidence threshold",
                "${(settings.getEdgeConfidenceThreshold() * 100).toInt()}%",
            ) {
                showBrandedConfidencePage()
            }
        }
    }

    internal fun showBrandedDetectionSourcePage() {
        val sources = listOf(DetectionSource.DJI_ONBOARD, DetectionSource.YOLO_ON_PHONE)
        settingsDialogViews.showBrandedChoicePage(
            "DETECTION SOURCE",
            "Choose where target detections are produced",
            sources.map { it.menuLabel },
            sources.indexOf(settings.getDetectionSource()).coerceAtLeast(0),
            onSelected = { index -> setDetectionSource(sources[index]) },
            returnPage = ::showBrandedDetectionSettingsPage,
            onBack = ::showBrandedDetectionSettingsPage,
        )
    }

    internal fun showBrandedConfidencePage() {
        settingsDialogViews.showBrandedChoicePage(
            "CONFIDENCE THRESHOLD",
            "Minimum confidence for reported targets",
            LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS.map { "${(it * 100).toInt()}%" },
            LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS
                .indexOfFirst { kotlin.math.abs(it - settings.getEdgeConfidenceThreshold()) < 0.001f }
                .coerceAtLeast(0),
            onSelected = { index ->
                val threshold = LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS[index]
                sharedPreferences.edit().putFloat(LyrebirdSettings.PREF_EDGE_CONFIDENCE_THRESHOLD, threshold).apply()
                applyEdgeConfidenceSelection(threshold)
            },
            returnPage = ::showBrandedDetectionSettingsPage,
            onBack = ::showBrandedDetectionSettingsPage,
        )
    }

    internal fun showBrandedMavlinkPage() {
        if (isMavlinkFlightAllowed()) {
            setMavlinkFlightAllowed(false)
            showLyrebirdSettingsMenu()
            return
        }
        settingsDialogViews.showBrandedSettingsSubpage(
            "MAVLINK FLIGHT CONTROL",
            "This grants command authority to connected ground stations",
        ) { container, dialog ->
            settingsDialogViews.addBrandedSettingsSection(container, "SAFETY")
            settingsDialogViews.addBrandedSettingsRow(
                container,
                "Allow flight commands",
                "Takeoff, landing, RTH and missions will be accepted",
                R.drawable.uxsdk_ic_drone,
            )
            settingsDialogViews.addBrandedSettingsButton(container, "Allow flight control", {
                setMavlinkFlightAllowed(true)
                showLyrebirdSettingsMenu()
            })
        }
    }

    internal fun showBrandedFormatStoragePage(
        location: AircraftStorage,
        label: String,
    ) {
        val status = getDroneStorageStatus(location, label)
        settingsDialogViews.showBrandedSettingsSubpage("FORMAT $label".uppercase(), "Destructive media operation") { container, dialog ->
            settingsDialogViews.addBrandedSettingsSection(container, "CURRENT STATUS")
            settingsDialogViews.addBrandedSettingsRow(container, status.label, status.summary, R.drawable.uxsdk_ic_sdcard)
            container.addView(
                TextView(this).apply {
                    text = "This deletes all media on the drone $label. Stop recording first, then continue only if you are sure."
                    setTextColor(ContextCompat.getColor(this@FlightDeckSettingsPages, R.color.lyrebird_danger))
                    textSize = 14f
                    setPadding(0, settingsDialogViews.dpToPx(8), 0, settingsDialogViews.dpToPx(18))
                },
            )
            settingsDialogViews.addBrandedSettingsButton(container, "Format $label", {
                dialog.dismiss()
                formatDroneStorage(location, label)
            }, destructive = true)
        }
    }

    internal fun showDroneNameDialog(isFirstTime: Boolean = false) {
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    settingsDialogViews.dpToPx(16),
                    settingsDialogViews.dpToPx(8),
                    settingsDialogViews.dpToPx(16),
                    settingsDialogViews.dpToPx(8),
                )
            }
        val nameInput =
            EditText(this).apply {
                hint = "e.g., lb_01, alpha, scout (blank = automatic)"
                if (!isFirstTime) setText(droneName)
            }
        container.addView(nameInput)
        container.addView(settingsDialogViews.identityHelpText(getString(R.string.drone_name_help)))

        val configuredSysId =
            prefIntOrDefault(
                MavlinkEndpointConfig.PREF_SYSTEM_ID,
                MavlinkEndpointConfig.DEFAULT_SYSTEM_ID,
            )
        val idInput =
            EditText(this).apply {
                hint = "0 = automatic, 1-99 = manual"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(if (MavlinkSystemId.isManual(configuredSysId)) configuredSysId.toString() else "")
            }
        container.addView(idInput)
        container.addView(settingsDialogViews.identityHelpText(getString(R.string.vehicle_id_help)))

        val builder =
            AlertDialog
                .Builder(this)
                .setTitle(if (isFirstTime) "Drone Identity" else "Change Drone Identity")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val name = nameInput.text.toString().trim()
                    if (name.isNotEmpty()) {
                        if (setDroneName(name)) {
                            Toast.makeText(this, "Drone name saved: $droneName", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!isFirstTime) {
                        setAutomaticDroneName()
                    }
                    val idText = idInput.text.toString().trim()
                    val id = if (idText.isBlank()) MavlinkSystemId.AUTO else idText.toIntOrNull()
                    if (id != null && (id == MavlinkSystemId.AUTO || MavlinkSystemId.isManual(id))) {
                        setMavlinkSystemId(id)
                    } else {
                        Toast.makeText(this, "Enter 0 for automatic, or 1-99 for a manual ID", Toast.LENGTH_SHORT).show()
                    }
                }

        if (isFirstTime) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton("Cancel", null)
        }

        builder.show()
    }

    internal fun showMediamtxServerDialog() {
        val input = EditText(this)
        val current = sharedPreferences.getString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, "").orEmpty()
        input.hint = "host o host:puerto (ej: 10.233.132.21:8889)"
        input.setText(current)

        AlertDialog
            .Builder(this)
            .setTitle("WHIP / mediamtx server")
            .setMessage("Opcional: si se deja vacío, se usa la IP del primer cliente de telemetría.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                sharedPreferences.edit().putString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, value).apply()
                val shown = if (value.isEmpty()) "auto (client IP)" else value
                Log.i(TAG, "Mediamtx server set to: $shown")
                Toast.makeText(this, "WHIP server: $shown", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showWebRTCFpsDialog() {
        val currentFps = settings.getWebRTCFps()
        val labels = LyrebirdSettings.WEBRTC_FPS_OPTIONS.map { "$it fps" }.toTypedArray()
        val checkedIndex = LyrebirdSettings.WEBRTC_FPS_OPTIONS.indexOf(currentFps).coerceAtLeast(0)

        AlertDialog
            .Builder(this)
            .setTitle("WebRTC frame rate")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedFps = LyrebirdSettings.WEBRTC_FPS_OPTIONS[which]
                sharedPreferences.edit().putInt(LyrebirdSettings.PREF_WEBRTC_FPS, selectedFps).apply()
                changeVideoOptions()
                Toast.makeText(this, "WebRTC FPS: $selectedFps", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "WebRTC frame rate set to $selectedFps fps")
                dialog.dismiss()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showWebRTCResolutionDialog() {
        val presets = StreamResolutionPreset.entries.toTypedArray()
        val labels =
            presets
                .map {
                    if (it.width > 0 && it.height > 0) "${it.menuLabel} (${it.width}x${it.height})" else it.menuLabel
                }.toTypedArray()
        val checkedIndex = presets.indexOf(settings.getWebRTCResolutionPreset()).coerceAtLeast(0)

        AlertDialog
            .Builder(this)
            .setTitle("WebRTC resolution")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedPreset = presets[which]
                sharedPreferences.edit().putString(LyrebirdSettings.PREF_WEBRTC_RESOLUTION, selectedPreset.prefValue).apply()
                changeVideoOptions()
                Toast.makeText(this, "WebRTC resolution: ${selectedPreset.menuLabel}", Toast.LENGTH_SHORT).show()
                Log.i(
                    TAG,
                    "WebRTC resolution set to ${if (
                        selectedPreset.width > 0 && selectedPreset.height > 0
                    ) {
                        "${selectedPreset.width}x${selectedPreset.height}"
                    } else {
                        "native source"
                    }}",
                )
                dialog.dismiss()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showStreamSettingsDialog() {
        val mode = settings.getStreamingMode()
        val rows = mutableListOf<SettingsDialogViews.SettingsActionRow>()
        rows.add(SettingsDialogViews.SettingsActionRow("Streaming protocol", mode.menuLabel))

        when (mode) {
            StreamingMode.WEBRTC -> {
                val configuredServer = sharedPreferences.getString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, "")?.trim().orEmpty()
                val serverLabel = configuredServer.ifEmpty { "Auto" }
                rows.add(SettingsDialogViews.SettingsActionRow("WHIP server", serverLabel))
                rows.add(SettingsDialogViews.SettingsActionRow("WebRTC FPS", "${settings.getWebRTCFps()} fps"))
                rows.add(SettingsDialogViews.SettingsActionRow("WebRTC resolution", settings.getWebRTCResolutionPreset().menuLabel))
                rows.add(
                    SettingsDialogViews.SettingsActionRow(
                        "Surface H264 encoder",
                        if (settings.isDjiSurfaceH264EncoderEnabled()) {
                            "Experimental / enabled (restart required)"
                        } else {
                            "Default WebRTC encoder"
                        },
                    ),
                )
            }
            StreamingMode.RTMP -> {
                val rtmpUrl = getRtmpUrl(NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1")
                rows.add(SettingsDialogViews.SettingsActionRow("RTMP Server URL", rtmpUrl))
            }
            StreamingMode.RTSP -> {
                val port = settings.getRtspPort()
                rows.add(SettingsDialogViews.SettingsActionRow("RTSP Config", "Port $port"))
            }
            StreamingMode.AGORA -> {
                val channel = settings.getAgoraChannel().ifEmpty { "None" }
                rows.add(SettingsDialogViews.SettingsActionRow("Agora.io Config", "Channel: $channel"))
            }
            StreamingMode.GB28181 -> {
                val ip = settings.getGbServerIp().ifEmpty { "None" }
                rows.add(SettingsDialogViews.SettingsActionRow("GB28181 Config", "Server: $ip"))
            }
        }

        AlertDialog
            .Builder(this)
            .setTitle("Video Streaming Configuration")
            .setAdapter(settingsDialogViews.actionRowAdapter(rows)) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    showStreamingModeDialog()
                } else {
                    when (mode) {
                        StreamingMode.WEBRTC -> {
                            when (which) {
                                1 -> showMediamtxServerDialog()
                                2 -> showWebRTCFpsDialog()
                                3 -> showWebRTCResolutionDialog()
                                4 -> toggleDjiSurfaceH264Encoder()
                            }
                        }
                        StreamingMode.RTMP -> showRtmpConfigDialog()
                        StreamingMode.RTSP -> showRtspConfigDialog()
                        StreamingMode.AGORA -> showAgoraConfigDialog()
                        StreamingMode.GB28181 -> showGb28181ConfigDialog()
                    }
                }
            }.setNegativeButton("Close", null)
            .show()
    }

    internal fun showStreamingModeDialog() {
        val modes = StreamingMode.entries.toTypedArray()
        val labels = modes.map { it.menuLabel }.toTypedArray()
        val checkedIndex = modes.indexOf(settings.getStreamingMode()).coerceAtLeast(0)

        AlertDialog
            .Builder(this)
            .setTitle("Select Streaming Protocol")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedMode = modes[which]
                setStreamingMode(selectedMode)
                dialog.dismiss()
                if (shouldRestartActiveStreaming()) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showRtmpConfigDialog() {
        val input =
            EditText(this).apply {
                setText(getRtmpUrl(NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"))
                hint = "rtmp://<host>:<port>/live/stream_id"
            }
        AlertDialog
            .Builder(this)
            .setTitle("Configure RTMP URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                settings.setRtmpUrl(url)
                if (url.isNotEmpty() && (shouldRestartActiveStreaming())) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }.setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    internal fun showRtspConfigDialog() {
        val context = this
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
        val portInput =
            EditText(context).apply {
                setText(settings.getRtspPort().toString())
                hint = "RTSP Server Port (e.g. 8554)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        val userInput =
            EditText(context).apply {
                setText(settings.getRtspUsername())
                hint = "Username (Optional)"
            }
        val pwdInput =
            EditText(context).apply {
                setText(settings.getRtspPassword())
                hint = "Password (Optional)"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        layout.addView(TextView(context).apply { text = "RTSP Server Port" })
        layout.addView(portInput)
        layout.addView(TextView(context).apply { text = "Username" })
        layout.addView(userInput)
        layout.addView(TextView(context).apply { text = "Password" })
        layout.addView(pwdInput)

        AlertDialog
            .Builder(context)
            .setTitle("RTSP Server Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val port = portInput.text.toString().toIntOrNull() ?: 8554
                settings.setRtspPort(port)
                settings.setRtspUsername(userInput.text.toString())
                settings.setRtspPassword(pwdInput.text.toString())
                if (shouldRestartActiveStreaming()) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }.setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    internal fun showAgoraConfigDialog() {
        val context = this
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
        val channelInput =
            EditText(context).apply {
                setText(settings.getAgoraChannel())
                hint = "Agora Channel Name"
            }
        val tokenInput =
            EditText(context).apply {
                setText(settings.getAgoraToken())
                hint = "Agora Token (Optional)"
            }
        val uidInput =
            EditText(context).apply {
                setText(settings.getAgoraUid())
                hint = "Agora User ID (UID, e.g. 0)"
            }
        layout.addView(TextView(context).apply { text = "Channel ID" })
        layout.addView(channelInput)
        layout.addView(TextView(context).apply { text = "Token" })
        layout.addView(tokenInput)
        layout.addView(TextView(context).apply { text = "User ID (UID)" })
        layout.addView(uidInput)

        AlertDialog
            .Builder(context)
            .setTitle("Agora.io Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                settings.setAgoraChannel(channelInput.text.toString())
                settings.setAgoraToken(tokenInput.text.toString())
                settings.setAgoraUid(uidInput.text.toString())
                if (shouldRestartActiveStreaming()) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }.setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    internal fun showGb28181ConfigDialog() {
        val context = this
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
        val ipInput =
            EditText(context).apply {
                setText(settings.getGbServerIp())
                hint = "SIP Server IP"
            }
        val portInput =
            EditText(context).apply {
                setText(settings.getGbServerPort().toString())
                hint = "SIP Server Port (e.g. 5060)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        val serverIdInput =
            EditText(context).apply {
                setText(settings.getGbServerId())
                hint = "SIP Server ID (20 characters)"
            }
        val agentIdInput =
            EditText(context).apply {
                setText(settings.getGbAgentId())
                hint = "SIP Agent ID (20 characters)"
            }
        val channelInput =
            EditText(context).apply {
                setText(settings.getGbChannel())
                hint = "Video Channel ID (20 characters)"
            }
        val localPortInput =
            EditText(context).apply {
                setText(settings.getGbLocalPort().toString())
                hint = "Local SIP Port (e.g. 5061)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        val pwdInput =
            EditText(context).apply {
                setText(settings.getGbPassword())
                hint = "Password"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

        val scroll =
            android.widget.ScrollView(context).apply {
                addView(layout)
            }

        layout.addView(TextView(context).apply { text = "SIP Server IP" })
        layout.addView(ipInput)
        layout.addView(TextView(context).apply { text = "SIP Server Port" })
        layout.addView(portInput)
        layout.addView(TextView(context).apply { text = "Server ID" })
        layout.addView(serverIdInput)
        layout.addView(TextView(context).apply { text = "Agent ID" })
        layout.addView(agentIdInput)
        layout.addView(TextView(context).apply { text = "Channel ID" })
        layout.addView(channelInput)
        layout.addView(TextView(context).apply { text = "Local Port" })
        layout.addView(localPortInput)
        layout.addView(TextView(context).apply { text = "Password" })
        layout.addView(pwdInput)

        AlertDialog
            .Builder(context)
            .setTitle("GB28181 Configuration")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                settings.setGbServerIp(ipInput.text.toString())
                settings.setGbServerPort(portInput.text.toString().toIntOrNull() ?: 5060)
                settings.setGbServerId(serverIdInput.text.toString())
                settings.setGbAgentId(agentIdInput.text.toString())
                settings.setGbChannel(channelInput.text.toString())
                settings.setGbLocalPort(localPortInput.text.toString().toIntOrNull() ?: 5061)
                settings.setGbPassword(pwdInput.text.toString())
                if (shouldRestartActiveStreaming()) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }.setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    internal fun showEdgeConfidenceDialog() {
        val currentThreshold = settings.getEdgeConfidenceThreshold()
        val labels = LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS.map { "${(it * 100).toInt()}%" }.toTypedArray()
        val checkedIndex =
            LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS
                .indexOfFirst { kotlin.math.abs(it - currentThreshold) < 0.001f }
                .takeIf { it >= 0 }
                ?: LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS
                    .indexOfFirst { kotlin.math.abs(it - LyrebirdSettings.DEFAULT_EDGE_CONFIDENCE_THRESHOLD) < 0.001f }
                    .coerceAtLeast(0)

        AlertDialog
            .Builder(this)
            .setTitle("Edge confidence threshold")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedThreshold = LyrebirdSettings.EDGE_CONFIDENCE_OPTIONS[which]
                sharedPreferences.edit().putFloat(LyrebirdSettings.PREF_EDGE_CONFIDENCE_THRESHOLD, selectedThreshold).apply()
                applyEdgeConfidenceSelection(selectedThreshold)
                invalidateOptionsMenu()
                Toast
                    .makeText(
                        this,
                        "Edge confidence: ${(selectedThreshold * 100).toInt()}%",
                        Toast.LENGTH_SHORT,
                    ).show()
                Log.i(TAG, "Edge confidence threshold set to $selectedThreshold")
                dialog.dismiss()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showFormatStorageDialog(
        location: AircraftStorage,
        label: String,
    ) {
        val status = getDroneStorageStatus(location, label)
        AlertDialog
            .Builder(this)
            .setTitle("Format $label")
            .setMessage(
                "${status.dialogText}\n\nThis deletes all media on the drone $label. " +
                    "Stop recording first, then continue only if you are sure.",
            ).setPositiveButton("Format") { _, _ ->
                formatDroneStorage(location, label)
            }.setNegativeButton("Cancel", null)
            .show()
    }
}
