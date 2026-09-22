package com.lyrebird.rc.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lyrebird.rc.DJIAircraftMainActivity
import com.lyrebird.rc.R

/**
 * Keeps the process-owned ground-station runtimes schedulable while no screen is visible.
 *
 * Moving fleet, HTTP, MAVLink, and telemetry ownership out of an Activity prevents lifecycle
 * teardown from stopping them, but it does not prevent Android from freezing a cached process.
 * This is deliberately a connected-device foreground service: Lyrebird is actively serving a
 * controller attached to DJI hardware and peers on the local network, and that work must remain
 * visible to the operator and live while the UI is in the background.
 */
internal class ProcessRuntimeService : Service() {
    companion object {
        private const val CHANNEL_ID = "lyrebird_process_runtime"
        private const val NOTIFICATION_ID = 42

        /**
         * The operator's explicit "stop serving": tear the runtimes down and give the device
         * lease back, in that order (see [ProcessAppRuntime.stopNetworkSession]).
         */
        const val ACTION_STOP = "com.lyrebird.rc.action.STOP_SERVING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, ProcessRuntimeService::class.java),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            ProcessAppRuntime.stopNetworkSession()
            stopSelf()
            return START_NOT_STICKY
        }
        ProcessAppRuntime.startNetworkSession(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.runtime_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_main)
            .setContentTitle(getString(R.string.runtime_notification_title))
            .setContentText(getString(R.string.runtime_notification_text))
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.runtime_notification_stop), stopServingIntent())
            .build()

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, DJIAircraftMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The stop action delivers its intent to this service rather than stopping the process:
     * releasing the lease without tearing the runtimes down first is exactly the ordering bug
     * this action exists to avoid.
     */
    private fun stopServingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, ProcessRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
