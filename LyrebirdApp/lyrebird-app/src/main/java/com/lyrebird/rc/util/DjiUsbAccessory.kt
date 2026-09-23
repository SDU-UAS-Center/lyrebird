package com.lyrebird.rc.util

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

/**
 * Whether a DJI RC is attached to this phone over USB.
 *
 * The RC presents itself as an Android USB *accessory*, and one app holds it open at a time.
 * While another DJI app has it - DJI Fly is the one that gets opened to check the hardware - the
 * SDK in this app still registers but sees no aircraft: the aircraft link reads as dead, and the
 * other app "proving" the drone works makes it look like this app's fault.
 *
 * Measured on the bench (mini5, 2026-09-22): with DJI Fly holding the accessory the telemetry
 * stream dropped to heartbeats alone, and it did not recover when that app was force-stopped -
 * only re-opening this app did. That is why the screen says what is wrong, and why its button
 * closes the other app rather than asking the operator to guess.
 *
 * Listing accessories needs no permission; only opening one does, and that is the SDK's job.
 */
object DjiUsbAccessory {
    private const val DJI_MANUFACTURER = "DJI"

    /** The attached DJI accessory, or null when none is attached or the list is unavailable. */
    fun attached(context: Context): UsbAccessory? =
        runCatching {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            manager?.accessoryList?.firstOrNull { it.manufacturer?.contains(DJI_MANUFACTURER, true) == true }
        }.getOrNull()
}
