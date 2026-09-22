package com.lyrebird.rc.telemetry

import android.content.Context
import android.content.ContextWrapper

/**
 * A context that answers only what [DeviceStatusSource] asks of the framework: nothing.
 *
 * The source resolves its managers through `getSystemService`, so a null answer here is the shape
 * of a device that has none of them — which is exactly the case the wire defaults exist for, and
 * the one a host JVM test can reproduce without a device or a mock framework.
 */
internal class DeviceServicesContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any? = null
}
