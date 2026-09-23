package com.lyrebird.rc

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lyrebird.rc.settings.LyrebirdOnboarding
import com.lyrebird.rc.server.DeviceSessionLeaseRegistry
import com.lyrebird.rc.util.DjiUsbAccessory
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

    /** What the home screen's title block is saying about the drone; see [showDroneNotice]. */
    private enum class DroneNotice { DETECTING, DETECTED, OPENING, ABSENT }

    private var droneNotice: DroneNotice? = null

    /** Animators of the notice's pulse; an empty list also means "no pulse is running". */
    private val noticeAnimators = mutableListOf<Animator>()
    private var detectDots = 0
    private var absentNoticeDue = false

    /** The UXSDK screens need their init before the deck can open; see [prepareUxActivity]. */
    private var uxPrepared = false
    private var flightDeckOpenPending = false
    private var flightDeckOpenCancelled = false
    private var flightDeckAutoLaunched = false
    private var reopenFlightDeckPending = false

    /**
     * Polls for drone registration while the main activity is resumed and keeps the title block
     * honest about what it finds: "looking for a drone" until the SDK answers, then either the
     * detected state (with the automatic open, see [onDroneConnected]) or the red absent notice.
     */
    private val connectionCheck = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val connected = isDroneConnected()
            if (connected) {
                absentNoticeDue = false
                detectDots = 0
                onDroneConnected()
            } else {
                showDroneNotice(if (absentNoticeDue) DroneNotice.ABSENT else DroneNotice.DETECTING)
            }
            recoveryHandler.postDelayed(this, if (connected) SETTLED_POLL_MS else CONNECTION_POLL_MS)
        }
    }

    /**
     * Turns "still nothing" into the red absent notice. Deliberately later than the watcher
     * starts: DJI registration takes a few seconds after the screen appears, and flagging that
     * window would be a false alarm.
     */
    private val droneAbsentNotice = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (!isDroneConnected()) {
            absentNoticeDue = true
            showDroneNotice(DroneNotice.ABSENT)
        }
    }

    /**
     * The automatic open itself, announced for a moment first so the operator can cancel it by
     * pressing anywhere on the screen.
     */
    private val openFlightDeck = Runnable {
        flightDeckOpenPending = false
        if (isFinishing || isDestroyed || flightDeckOpenCancelled) return@Runnable
        // A modal in the way, or a drone that went away, leaves it to the next watcher tick.
        if (!isDroneConnected() || isLyrebirdModalShowing()) return@Runnable
        try {
            startActivity(Intent(this, FlightDeckActivity::class.java))
            flightDeckAutoLaunched = true
        } catch (_: Exception) {
            // Left to the watcher: the next tick announces and retries while the drone is here.
        }
    }

    private fun startConnectionWatcher() {
        recoveryHandler.removeCallbacks(connectionCheck)
        recoveryHandler.post(connectionCheck)
    }

    private fun scheduleDroneAbsentNotice() {
        recoveryHandler.removeCallbacks(droneAbsentNotice)
        recoveryHandler.postDelayed(droneAbsentNotice, DRONE_ABSENT_NOTICE_DELAY_MS)
    }

    /**
     * A drone is registered: the title block says so, and the deck opens by itself unless the
     * operator cancelled that, it already opened, a modal is in the way, or the UXSDK screens are
     * not initialised yet. Announcing an automatic open that a dialog would hide (or opening
     * under one the operator is still reading) is worse than waiting a moment for it.
     */
    private fun onDroneConnected() {
        if (flightDeckAutoLaunched || flightDeckOpenCancelled || isLyrebirdModalShowing() || !uxPrepared) {
            cancelPendingFlightDeckOpen()
            showDroneNotice(DroneNotice.DETECTED)
            return
        }
        showDroneNotice(DroneNotice.OPENING)
        if (flightDeckOpenPending) return
        flightDeckOpenPending = true
        recoveryHandler.postDelayed(openFlightDeck, FLIGHT_DECK_AUTO_OPEN_DELAY_MS)
    }

    private fun cancelPendingFlightDeckOpen() {
        recoveryHandler.removeCallbacks(openFlightDeck)
        flightDeckOpenPending = false
    }

    /**
     * Any press on this screen means the operator wants to stay where they are: the automatic
     * open is off for the rest of this screen's life, and the notice says so.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && flightDeckOpenPending) {
            flightDeckOpenCancelled = true
            cancelPendingFlightDeckOpen()
            showDroneNotice(DroneNotice.DETECTED)
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Paints the drone state into the home screen's title block: the brand changes colour, the
     * row under it carries the state line and, for a missing drone, the re-open pill, and the
     * line under that says what to do about it.
     *
     * Called on every watcher tick, so the look is applied only when the state actually changes:
     * repainting per tick would restart the pulse on its first frame.
     */
    private fun showDroneNotice(notice: DroneNotice) {
        val brand = findViewById<TextView>(R.id.title_brand) ?: return
        val status = findViewById<TextView>(R.id.title_drone_status) ?: return
        val hint = findViewById<TextView>(R.id.title_drone_hint) ?: return
        val reopen = findViewById<TextView>(R.id.home_reopen_button) ?: return
        if (droneNotice != notice) {
            droneNotice = notice
            stopNoticePulse()
            val tint = ContextCompat.getColor(this, brandColour(notice))
            brand.setTextColor(tint)
            status.setTextColor(tint)
            hint.setTextColor(tint)
            status.visibility = View.VISIBLE
            status.setText(statusText(notice))
            val hintRes = hintText(notice)
            hint.visibility = if (hintRes == null) View.GONE else View.VISIBLE
            if (hintRes != null) hint.setText(hintRes)
            reopen.alpha = 1f
            reopen.visibility = if (notice == DroneNotice.ABSENT) View.VISIBLE else View.GONE
            when (notice) {
                DroneNotice.DETECTING ->
                    startNoticePulse(ObjectAnimator.ofFloat(status, View.ALPHA, 1f, DETECTING_MIN_ALPHA))

                DroneNotice.ABSENT -> {
                    // Two rungs on the same ladder: an accessory nobody can open means another DJI
                    // app is holding the RC, and a missing cable means just that. The button does
                    // the half this app can do reliably - re-open itself, which re-attaches the
                    // SDK - and the hint says who has to close the other app, because Android
                    // refuses to kill a foreground process and some builds restart the one that
                    // took the accessory (measured: ColorOS restarted DJI Fly either way).
                    val rcBusy = DjiUsbAccessory.attached(this) != null
                    hint.setText(if (rcBusy) R.string.drone_status_absent_rc_busy else R.string.drone_status_absent_hint)
                    reopen.setOnClickListener { reopenLyrebird() }
                    val danger = ContextCompat.getColor(this, R.color.lyrebird_danger)
                    val glow = ContextCompat.getColor(this, R.color.lyrebird_danger_glow)
                    startNoticePulse(
                        ObjectAnimator.ofArgb(brand, "textColor", danger, glow),
                        ObjectAnimator.ofArgb(status, "textColor", danger, glow),
                        ObjectAnimator.ofArgb(hint, "textColor", danger, glow),
                        ObjectAnimator.ofFloat(reopen, View.ALPHA, 1f, ABSENT_MIN_ALPHA),
                    )
                }

                DroneNotice.DETECTED, DroneNotice.OPENING -> Unit
            }
        }
        if (notice == DroneNotice.DETECTING) {
            // Dots advancing with the watcher are the "still looking" animation.
            detectDots = (detectDots + 1) % 4
            status.text = getString(R.string.drone_status_detecting) + ".".repeat(detectDots)
        }
    }

    private fun brandColour(notice: DroneNotice): Int =
        when (notice) {
            DroneNotice.DETECTING -> R.color.lyrebird_orange
            DroneNotice.DETECTED, DroneNotice.OPENING -> R.color.lyrebird_ok
            DroneNotice.ABSENT -> R.color.lyrebird_danger
        }

    private fun statusText(notice: DroneNotice): Int =
        when (notice) {
            DroneNotice.DETECTING -> R.string.drone_status_detecting
            DroneNotice.DETECTED, DroneNotice.OPENING -> R.string.drone_status_detected
            DroneNotice.ABSENT -> R.string.drone_status_absent
        }

    private fun hintText(notice: DroneNotice): Int? =
        when (notice) {
            DroneNotice.OPENING -> R.string.drone_status_opening
            DroneNotice.DETECTED -> if (flightDeckOpenCancelled) R.string.drone_status_open_cancelled else null
            DroneNotice.ABSENT -> R.string.drone_status_absent_hint
            DroneNotice.DETECTING -> null
        }

    /** Fades the given animators between their values and back, for as long as they are shown. */
    private fun startNoticePulse(vararg animators: ObjectAnimator) {
        animators.forEach { animator ->
            noticeAnimators += animator.pulsing()
        }
        noticeAnimators.forEach { it.start() }
    }

    private fun stopNoticePulse() {
        noticeAnimators.forEach { it.cancel() }
        noticeAnimators.clear()
    }

    private fun ObjectAnimator.pulsing(): ObjectAnimator = apply {
        duration = NOTICE_PULSE_MS
        interpolator = AccelerateDecelerateInterpolator()
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }

    private fun reopenFlightDeckAfterRestart() {
        flightDeckAutoLaunched = true
        reopenFlightDeckPending = true
        recoveryHandler.removeCallbacks(connectionCheck)
        recoveryHandler.postDelayed({
            if (!reopenFlightDeckPending || isFinishing || isDestroyed) return@postDelayed
            reopenFlightDeckPending = false
            if (!isDroneConnected()) {
                flightDeckAutoLaunched = false
                startConnectionWatcher()
                return@postDelayed
            }
            try {
                startActivity(Intent(this, FlightDeckActivity::class.java))
            } catch (_: Exception) {
                flightDeckAutoLaunched = false
                startConnectionWatcher()
            }
        }, FLIGHT_DECK_REOPEN_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REOPEN_FLIGHT_DECK, false)) {
            reopenFlightDeckAfterRestart()
        }
    }

    override fun prepareUxActivity() {
        UxSharedPreferencesUtil.initialize(this)
        GlobalPreferencesManager.initialize(DefaultGlobalPreferences(this))
        GeoidManager.getInstance().init(this)

        enableFlightDeck(FlightDeckActivity::class.java)
        enableWidgetList(WidgetsActivity::class.java)

        // The UXSDK screens need this init, so the automatic open waits for it and opens on the
        // watcher's next tick (see onDroneConnected).
        uxPrepared = true
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
        // Another Lyrebird app owns the device session: this process has no DJI SDK, so there is
        // nothing for the onboarding prompts or the drone-connection watcher to talk to. The
        // launcher screen itself explains the situation (see DJIMainActivity).
        if (DeviceSessionLeaseRegistry.blockedByAnotherSession) return
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
        scheduleDroneAbsentNotice()
        startConnectionWatcher()
    }

    override fun onPause() {
        reopenFlightDeckPending = false
        // The notice and its pulse exist on screen only: drop them and let the watcher repaint
        // whatever is still true when this screen comes back.
        stopNoticePulse()
        droneNotice = null
        cancelPendingFlightDeckOpen()
        recoveryHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        stopNoticePulse()
        recoveryHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reopenLyrebird() {
        val restartIntent = Intent(this, DJIAircraftMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(restartIntent)
        finishAffinity()
    }

    companion object {
        internal const val EXTRA_REOPEN_FLIGHT_DECK = "com.lyrebird.rc.extra.REOPEN_FLIGHT_DECK"
        private const val DRONE_ABSENT_NOTICE_DELAY_MS = 12_000L
        private const val FLIGHT_DECK_AUTO_OPEN_DELAY_MS = 2_500L
        private const val NOTICE_PULSE_MS = 1_200L
        private const val DETECTING_MIN_ALPHA = 0.45f
        private const val ABSENT_MIN_ALPHA = 0.35f
        private const val CONNECTION_POLL_MS = 1_000L

        /** Connected is the quiescent state, so it is polled less often than a search. */
        private const val SETTLED_POLL_MS = 2_000L
        private const val FLIGHT_DECK_REOPEN_DELAY_MS = 500L
    }
}