package com.lyrebird.rc.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

internal data class DeviceStatusSnapshot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val heading: Double = 0.0,
    val pressure: Float = 0.0f,
    val battery: Int = -1,
    val wifiRssi: Int = -100,
) {
    fun applyTo(coordinator: TelemetryCoordinator) {
        coordinator.phoneLatitude = latitude
        coordinator.phoneLongitude = longitude
        coordinator.phoneHeading = heading
        coordinator.phonePressure = pressure
        coordinator.phoneBattery = battery
        coordinator.wifiRssi = wifiRssi
    }

    companion object {
        fun headingDegrees(azimuthRadians: Double): Double {
            val degrees = Math.toDegrees(azimuthRadians)
            return if (degrees < 0) degrees + 360.0 else degrees
        }
    }
}

internal class DeviceStatusSource(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @Volatile private var location: Location? = null

    @Volatile private var heading = 0.0

    @Volatile private var pressure = 0.0f
    private var locationStarted = false
    private var sensorsStarted = false
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val locationListener = PhoneLocationListener(this)
    private val sensorListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    Sensor.TYPE_PRESSURE -> pressure = event.values[0]
                }
                SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                heading = DeviceStatusSnapshot.headingDegrees(orientationAngles[0].toDouble())
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }

    private class PhoneLocationListener(
        source: DeviceStatusSource,
    ) : LocationListener {
        private val sourceRef = WeakReference(source)

        override fun onLocationChanged(location: Location) {
            sourceRef.get()?.location = location
        }

        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?,
        ) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun startLocationUpdates(): Boolean {
        if (locationStarted) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        runCatching {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)
            locationStarted = true
        }.onFailure { Log.e(TAG, "Error requesting location updates: ${it.message}", it) }
        return true
    }

    fun startSensorUpdates() {
        if (sensorsStarted) return
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PRESSURE).forEach { type ->
            sensorManager?.getDefaultSensor(type)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        sensorsStarted = true
    }

    fun snapshot(): DeviceStatusSnapshot {
        val currentLocation = location
        return DeviceStatusSnapshot(
            currentLocation?.latitude ?: 0.0,
            currentLocation?.longitude ?: 0.0,
            heading,
            pressure,
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
            currentWifiRssi(),
        )
    }

    fun stop() {
        runCatching { locationManager?.removeUpdates(locationListener) }
            .onFailure { Log.w(TAG, "Error removing location updates: ${it.message}") }
        runCatching { sensorManager?.unregisterListener(sensorListener) }
            .onFailure { Log.w(TAG, "Error unregistering sensor listener: ${it.message}") }
        locationStarted = false
        sensorsStarted = false
    }

    private fun currentWifiRssi(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork ?: return -100
            val capabilities = manager.getNetworkCapabilities(network) ?: return -100
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return -100
            return (capabilities.transportInfo as? WifiInfo)?.rssi ?: -100
        }
        @Suppress("DEPRECATION")
        return wifiManager?.connectionInfo?.rssi ?: -100
    }

    companion object {
        private const val TAG = "LyrebirdDeviceStatus"
    }
}
