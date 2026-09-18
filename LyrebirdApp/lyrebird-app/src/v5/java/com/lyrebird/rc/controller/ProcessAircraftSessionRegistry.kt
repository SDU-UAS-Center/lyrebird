package com.lyrebird.rc.controller

import com.lyrebird.rc.models.BasicAircraftControlVM
import com.lyrebird.rc.models.VirtualStickVM

/** Process-owned V5 control session used by the network and every control screen. */
internal object ProcessAircraftSessionRegistry {
    private val basicAircraftControlVM = BasicAircraftControlVM()
    private val virtualStickVM = VirtualStickVM()

    @Volatile
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        DroneController.init(basicAircraftControlVM, virtualStickVM)
        virtualStickVM.listenRCStick()
        started = true
    }

    fun basicAircraftControlVM(): BasicAircraftControlVM = basicAircraftControlVM

    fun virtualStickVM(): VirtualStickVM = virtualStickVM
}
