package com.lyrebird.rc

import android.content.Context
import android.os.StrictMode
import android.util.Log

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/2
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class DJIAircraftApplication : DJIApplication() {

    override fun attachBaseContext(base: Context?) {
        Log.d("DJIAircraftApp", "attachBaseContext() called")
        try {
            super.attachBaseContext(base)
            Log.d("DJIAircraftApp", "super.attachBaseContext() completed")
            com.cySdkyc.clx.Helper.install(this)
            Log.d("DJIAircraftApp", "Helper.install() completed")
        } catch (e: Exception) {
            Log.e("DJIAircraftApp", "Error in attachBaseContext: ${e.message}", e)
            throw e
        }
    }

    override fun onCreate() {
        Log.d("DJIAircraftApp", "DJIAircraftApplication onCreate() called")
        try {
            super.onCreate()
            installStrictModeInDebugBuilds()
            Log.d("DJIAircraftApp", "DJIAircraftApplication onCreate() completed successfully")
        } catch (e: Exception) {
            Log.e("DJIAircraftApp", "Error in DJIAircraftApplication onCreate: ${e.message}", e)
            throw e
        }
    }

    /**
     * Debug-only StrictMode policies. The app runs an HTTP command server and MAVLink writers,
     * so disk/network on the main thread and leaked Closables/Activities are real failure modes;
     * log (not crash) so a bench soak surfaces them without killing a live session.
     */
    private fun installStrictModeInDebugBuilds() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
        Log.d("DJIAircraftApp", "StrictMode enabled (debug build)")
    }
}