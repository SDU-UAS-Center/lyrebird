package com.lyrebird.rc

import android.app.Application
import android.util.Log
import com.lyrebird.rc.models.MSDKManagerVM
import com.lyrebird.rc.models.globalViewModels
import com.lyrebird.rc.server.DeviceSessionLeaseRegistry
import com.lyrebird.rc.server.ProcessAppRuntime

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class DJIApplication : Application() {

    private val msdkManagerVM: MSDKManagerVM by globalViewModels()

    override fun onCreate() {
        Log.d("DJIApplication", "onCreate() called")
        try {
            super.onCreate()
            Log.d("DJIApplication", "super.onCreate() completed")

            if (!DeviceSessionLeaseRegistry.acquire()) {
                Log.w("DJIApplication", "Another Lyrebird app owns the device session; SDK startup is deferred")
                return
            }

            // Ensure initialization is called first
            Log.d("DJIApplication", "Initializing Mobile SDK...")
            msdkManagerVM.initMobileSDK(this)
            Log.d("DJIApplication", "Mobile SDK initialization completed")

            // The network session belongs to the process, not to a screen: bring it up with the
            // SDK so a closed or restarting screen never takes the ground-station link with it.
            ProcessAppRuntime.startNetworkSession(this)
        } catch (e: Exception) {
            Log.e("DJIApplication", "Error in onCreate: ${e.message}", e)
            throw e
        }
    }

}
