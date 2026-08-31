package com.lyrebird.rc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.lyrebird.rc.settings.LyrebirdOnboarding
import dji.v5.common.utils.GeoidManager
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.ux.core.communication.DefaultGlobalPreferences
import dji.v5.ux.core.communication.GlobalPreferencesManager
import dji.v5.ux.core.util.UxSharedPreferencesUtil
import dji.v5.ux.sample.showcase.widgetlist.WidgetsActivity

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/2/14
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class DJIAircraftMainActivity : DJIMainActivity() {

    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var noDronePrompt: AlertDialog? = null
    private var noDronePromptShown = false
    private var flightDeckAutoLaunched = false

    /**
     * Polls for drone registration while the main activity is resumed. When a drone
     * registers, dismisses the no-drone dialog (if visible) and jumps into the
     * default layout so the user is not stuck on the recovery prompt.
     *
     * The default-layout button is only enabled 5 s after SDK registration (see
     * DJIMainActivity.observeSDKManager), so the open attempt is retried until the
     * layout actually opens.
     */
    private val connectionCheck = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val keepPolling = if (isDroneConnected()) {
                !onDroneConnected()
            } else {
                true
            }
            if (keepPolling) {
                recoveryHandler.postDelayed(this, CONNECTION_POLL_MS)
            }
        }
    }

    private fun startConnectionWatcher() {
        recoveryHandler.removeCallbacks(connectionCheck)
        recoveryHandler.post(connectionCheck)
    }

    /** Returns true when the default layout is open (or was already auto-launched). */
    private fun onDroneConnected(): Boolean {
        noDronePrompt?.dismiss()
        noDronePrompt = null
        noDronePromptShown = false
        if (flightDeckAutoLaunched) return true
        // Start the layout activity directly instead of clicking the showcase button:
        // the button's click listener is only attached after the SDK's delayed
        // prepareUxActivity(), so performClick() can silently no-op in that window.
        flightDeckAutoLaunched = true
        return try {
            startActivity(Intent(this, FlightDeckActivity::class.java))
            true
        } catch (_: Exception) {
            flightDeckAutoLaunched = false
            false
        }
    }

    override fun prepareUxActivity() {
        UxSharedPreferencesUtil.initialize(this)
        GlobalPreferencesManager.initialize(DefaultGlobalPreferences(this))
        GeoidManager.getInstance().init(this)

        enableFlightDeck(FlightDeckActivity::class.java)
        enableWidgetList(WidgetsActivity::class.java)

        openFlightDeckOnLaunchIfConnected()
    }

    /**
     * On app launch, jump straight into the default layout when a drone is connected
     * and the default layout button is accessible. Runs at most once per process so
     * the user is not bounced back into the layout after navigating away.
     */
    private fun openFlightDeckOnLaunchIfConnected() {
        if (flightDeckAutoLaunched) return
        if (!isDroneConnected()) return
        if (openFlightDeckIfAccessible()) {
            flightDeckAutoLaunched = true
        }
    }

    private fun isDroneConnected(): Boolean {
        return try {
            when (ProductKey.KeyProductType.create().get(ProductType.UNKNOWN)) {
                ProductType.UNKNOWN, ProductType.UNRECOGNIZED -> false
                else -> true
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun prepareTestingToolsActivity() {
        enableTestingTools(AircraftTestingToolsActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        // First-run prompts (file access + settings restore) live on the initial screen so the
        // operator handles them before a drone is connected; per-process guards make re-shows
        // a no-op.
        LyrebirdOnboarding.offerOnFirstRun(
            this,
            getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE),
        ) {
            // A restored drone name should appear on this screen right away.
            updateLyrebirdBuildInfo()
        }
        scheduleNoDroneRecoveryPrompt()
        startConnectionWatcher()
    }

    override fun onPause() {
        recoveryHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        noDronePrompt?.dismiss()
        noDronePrompt = null
        recoveryHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleNoDroneRecoveryPrompt() {
        if (noDronePromptShown || noDronePrompt?.isShowing == true) return
        recoveryHandler.removeCallbacksAndMessages(null)
        recoveryHandler.postDelayed({
            if (!isFinishing && !isDestroyed && shouldOfferNoDroneRecovery()) {
                showNoDroneRecoveryPrompt()
            }
        }, NO_DRONE_PROMPT_DELAY_MS)
    }

    private fun shouldOfferNoDroneRecovery(): Boolean = !isDroneConnected()

    private fun showNoDroneRecoveryPrompt() {
        noDronePromptShown = true
        noDronePrompt = AlertDialog.Builder(this)
            .setTitle("No drone detected")
            .setMessage("Lyrebird still does not see a drone. Make sure the drone is on, the controller is on, the controller USB-C port is connected properly, and DJI Fly or any other DJI app is not running in the background, then reopen Lyrebird to retry detection.")
            .setPositiveButton("Reopen Lyrebird") { _, _ ->
                reopenLyrebird()
            }
            .setNegativeButton("Keep waiting", null)
            .setOnDismissListener { noDronePrompt = null }
            .show()
    }

    private fun reopenLyrebird() {
        val restartIntent = Intent(this, DJIAircraftMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(restartIntent)
        finishAffinity()
    }

    companion object {
        private const val NO_DRONE_PROMPT_DELAY_MS = 12_000L
        private const val CONNECTION_POLL_MS = 1_000L
    }
}