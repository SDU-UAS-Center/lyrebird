package com.lyrebird.rc

import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.aircraft.payload.PayloadIndexType

enum class DroneControlProfile(
    val displayName: String,
    private val speedLimits: DroneSpeedLimits,
    private val distancePid: DronePidGains,
    private val yawControl: DroneYawControl,
    private val payloadDrop: DronePayloadDrop? = null
) {
    MAVIC_3_ENTERPRISE(
        displayName = "Mavic 3 Enterprise",
        speedLimits = DroneSpeedLimits(
            maxHorizontalSpeedMps = 20.0,
            maxHorizontalAccelMps2 = 1.0,
            defaultCruiseSpeedMps = 15.0
        ),
        distancePid = DronePidGains(kp = 0.65, ki = 0.0, kd = 0.001),
        yawControl = DroneYawControl(kp = 3.0, maxYawRateDegS = 30.0)
    ),
    MATRICE_300_RTK(
        displayName = "Matrice 300 RTK",
        speedLimits = DroneSpeedLimits(
            maxHorizontalSpeedMps = 20.0,
            maxHorizontalAccelMps2 = 1.0,
            defaultCruiseSpeedMps = 25.0
        ),
        distancePid = DronePidGains(kp = 0.35, ki = 0.0, kd = 0.001),
        yawControl = DroneYawControl(kp = 3.0, maxYawRateDegS = 30.0),
        // SkyPort release payload (TH4) sits on the RIGHT gimbal position; its drop is the
        // config-interface Unlock SWITCH (3) + All_Down BUTTON (5).
        payloadDrop = DronePayloadDrop(
            indexType = PayloadIndexType.RIGHT,
            armSwitchIndex = 3,
            releaseButtonIndex = 5
        )
    ),
    MATRICE_350_RTK(
        displayName = "Matrice 350 RTK",
        speedLimits = DroneSpeedLimits(
            maxHorizontalSpeedMps = 20.0,
            maxHorizontalAccelMps2 = 1.0,
            defaultCruiseSpeedMps = 3.0
        ),
        distancePid = DronePidGains(kp = 0.35, ki = 0.0, kd = 0.001),
        yawControl = DroneYawControl(kp = 3.0, maxYawRateDegS = 30.0),
        // Same SkyPort release payload as the M300: RIGHT + Unlock 3 / All_Down 5.
        payloadDrop = DronePayloadDrop(
            indexType = PayloadIndexType.RIGHT,
            armSwitchIndex = 3,
            releaseButtonIndex = 5
        )
    ),
    MATRICE_400(
        displayName = "Matrice 400",
        speedLimits = DroneSpeedLimits(
            maxHorizontalSpeedMps = 25.0,
            maxHorizontalAccelMps2 = 1.0,
            defaultCruiseSpeedMps = 25.0
        ),
        distancePid = DronePidGains(kp = 0.35, ki = 0.0, kd = 0.001),
        yawControl = DroneYawControl(kp = 3.0, maxYawRateDegS = 30.0),
        payloadDrop = DronePayloadDrop(
            indexType = PayloadIndexType.PORT_4,
            armSwitchIndex = 3,
            releaseButtonIndex = 5
        )
    ),
    MINI_4_PRO(
        displayName = "DJI Mini 4 Pro",
        speedLimits = DroneSpeedLimits(
            maxHorizontalSpeedMps = 15.0,
            maxHorizontalAccelMps2 = 1.0,
            defaultCruiseSpeedMps = 2.0
        ),
        distancePid = DronePidGains(kp = 0.65, ki = 0.0, kd = 0.001),
        yawControl = DroneYawControl(kp = 3.0, maxYawRateDegS = 30.0)
    );

    val maxHorizontalSpeedMps: Double get() = speedLimits.maxHorizontalSpeedMps
    val maxHorizontalAccelMps2: Double get() = speedLimits.maxHorizontalAccelMps2
    val defaultCruiseSpeedMps: Double get() = speedLimits.defaultCruiseSpeedMps
    val distanceKp: Double get() = distancePid.kp

    /**
     * Integral gain for the distance controller. Held at 0: the distance error is
     * sign-definite (always >= 0), so an integral term can only wind up and never
     * unwind, dumping into the output near the waypoint and causing overshoot.
     */
    val distanceKi: Double get() = distancePid.ki
    val distanceKd: Double get() = distancePid.kd
    val yawKp: Double get() = yawControl.kp
    val maxYawRateDegS: Double get() = yawControl.maxYawRateDegS

    /**
     * Payload SDK index (gimbal position / SkyPort) the release/drop payload is wired to,
     * or null if this drone carries no droppable payload. Consumed by the /send/drop endpoint.
     */
    val payloadIndexType: PayloadIndexType? get() = payloadDrop?.indexType

    /** Payload widget index /send/drop pulses first: the unlock SWITCH. */
    val dropArmSwitchIndex: Int get() = payloadDrop?.armSwitchIndex ?: DEFAULT_DROP_ARM_SWITCH_INDEX

    /** Payload widget index /send/drop pulses second: the release BUTTON. */
    val dropReleaseButtonIndex: Int get() =
        payloadDrop?.releaseButtonIndex ?: DEFAULT_DROP_RELEASE_BUTTON_INDEX

    private companion object {
        // The original working PORT_3 logic (M400 / M3E / M350) before the M300 SkyPort
        // payload (TH4) required its own config-interface Unlock 3 / All_Down 5 indices.
        const val DEFAULT_DROP_ARM_SWITCH_INDEX = 0
        const val DEFAULT_DROP_RELEASE_BUTTON_INDEX = 1
    }
}

private data class DroneSpeedLimits(
    val maxHorizontalSpeedMps: Double,
    val maxHorizontalAccelMps2: Double,
    val defaultCruiseSpeedMps: Double
)

private data class DronePidGains(
    val kp: Double,
    val ki: Double,
    val kd: Double
)

private data class DroneYawControl(
    val kp: Double,
    val maxYawRateDegS: Double
)

private data class DronePayloadDrop(
    val indexType: PayloadIndexType,
    val armSwitchIndex: Int,
    val releaseButtonIndex: Int
)

object DroneControlProfiles {
    fun activeProfile(): DroneControlProfile {
        val detected = ProductKey.KeyProductType.create().get(ProductType.UNKNOWN)
        return fromProductType(detected)
    }

    fun fromProductType(productType: ProductType?): DroneControlProfile {
        val name = productType?.name.orEmpty()
        return when {
            name.contains("M400", ignoreCase = true) ||
            name.contains("MATRICE_400", ignoreCase = true) -> DroneControlProfile.MATRICE_400

            name.contains("M350", ignoreCase = true) ||
            name.contains("MATRICE_350", ignoreCase = true) -> DroneControlProfile.MATRICE_350_RTK

            name.contains("M300", ignoreCase = true) ||
            name.contains("MATRICE_300", ignoreCase = true) -> DroneControlProfile.MATRICE_300_RTK

            name.contains("MINI_4", ignoreCase = true) ||
            name.contains("MINI4", ignoreCase = true) -> DroneControlProfile.MINI_4_PRO

            name.contains("MAVIC_3", ignoreCase = true) ||
            name.contains("MAVIC3", ignoreCase = true) ||
            name.contains("M3E", ignoreCase = true) ||
            name.contains("WM265", ignoreCase = true) -> DroneControlProfile.MAVIC_3_ENTERPRISE

            else -> DroneControlProfile.MAVIC_3_ENTERPRISE
        }
    }
}
