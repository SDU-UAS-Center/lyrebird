package com.lyrebird.rc

import android.app.Application
import android.util.Log
import com.lyrebird.rc.models.MSDKManagerVM
import com.lyrebird.rc.models.globalViewModels

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

            // Ensure initialization is called first
            Log.d("DJIApplication", "Initializing Mobile SDK...")
            msdkManagerVM.initMobileSDK(this)
            Log.d("DJIApplication", "Mobile SDK initialization completed")
        } catch (e: Exception) {
            Log.e("DJIApplication", "Error in onCreate: ${e.message}", e)
            throw e
        }
    }

}
