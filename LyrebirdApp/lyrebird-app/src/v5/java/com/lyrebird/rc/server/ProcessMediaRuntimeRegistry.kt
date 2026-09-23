package com.lyrebird.rc.server

import com.lyrebird.rc.models.MediaVM
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType

internal object ProcessMediaRuntimeRegistry {
    private val mediaVM = MediaVM()

    @Volatile
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        mediaVM.init()
        mediaVM.setStorage(CameraStorageLocation.SDCARD)
        mediaVM.setComponentIndex(ComponentIndexType.LEFT_OR_MAIN)
        started = true
    }

    fun mediaVM(): MediaVM {
        check(started) { "Process media runtime is not started" }
        return mediaVM
    }
}
