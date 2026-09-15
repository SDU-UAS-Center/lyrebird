package com.lyrebird.rc.telemetry

/*
 * Serialises the neutral readings into the JSON objects the telemetry wire has always carried.
 *
 * These exist because the previous producer was DJI's own `toString()`, which returns
 * `toJson().toString()` for its value classes. The bytes were therefore JSON objects with named
 * keys — `{"latitude":..,"longitude":..,"altitude":..}` — but the *shape* was a side effect of the
 * SDK's internal representation: invisible to this codebase, untestable without the SDK, and free
 * to change under it. Every consumer already treats these fields as objects, so the switch is
 * deliberately **shape-preserving**: same keys, same nesting, same absence rules, produced here
 * where they can be tested and reviewed.
 *
 * Two behaviours are matched on purpose, because they are part of what consumers already see:
 *
 *  - **Absent, not zero.** A reading the aircraft never reported is `null` at the field level. DJI's
 *    `toJson()` drops a key whose value is unset, and a null [Reading] means the same thing, so an
 *    absent key stays absent rather than becoming a plausible-looking `0`.
 *  - **Number rendering.** Numbers are written the way `org.json` writes them — integral values
 *    without a trailing `.0` — so the bytes do not change for a value that did not change.
 */

/**
 * Render a [Double] the way `org.json` does, so a value that has not changed does not change the
 * bytes it is sent as.
 *
 * `org.json` starts from `Double.toString` and then strips trailing zeros and a trailing point from
 * any plain-decimal form, which is why `30.0` reaches a ground station as `30`. Exponent forms are
 * left alone, matching too.
 *
 * `NaN` and the infinities have no JSON representation. `org.json` throws; this returns `null`,
 * because a telemetry frame is not a good place to discover a bad measurement by losing the whole
 * message — the field reads as "no value", which is also what it is.
 */
fun telemetryNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "null"
    var text = value.toString()
    if (text.contains('.') && !text.contains('e') && !text.contains('E')) {
        while (text.endsWith("0")) text = text.dropLast(1)
        if (text.endsWith(".")) text = text.dropLast(1)
    }
    return text
}

/** A position as `{latitude, longitude, altitude}`, or `null` when nothing was reported. */
fun GeoPosition?.toWireJson(): String =
    this?.let {
        """{"latitude":${telemetryNumber(it.latitudeDeg)},""" +
            """"longitude":${telemetryNumber(it.longitudeDeg)},""" +
            """"altitude":${telemetryNumber(it.altitudeAslM)}}"""
    } ?: "null"

/** A horizontal point as `{latitude, longitude}`, or `null`. */
fun GeoPoint?.toWireJson(): String =
    this?.let {
        """{"latitude":${telemetryNumber(it.latitudeDeg)},""" +
            """"longitude":${telemetryNumber(it.longitudeDeg)}}"""
    } ?: "null"

/** A measured target point as `{latitude, longitude, altitude}`, or `null`. */
fun GeoPoint3D?.toWireJson(): String =
    this?.let {
        """{"latitude":${telemetryNumber(it.latitudeDeg)},""" +
            """"longitude":${telemetryNumber(it.longitudeDeg)},""" +
            """"altitude":${telemetryNumber(it.altitudeM)}}"""
    } ?: "null"

/**
 * Attitude as `{pitch, roll, yaw}`, or `null`.
 *
 * Key order follows the SDK's declaration order, so the bytes match what the SDK produced for the
 * same values. Order is not significant to a JSON parser, but an unchanged frame producing
 * unchanged bytes is what makes a wire-format change reviewable.
 */
fun AttitudeDeg?.toWireJson(): String =
    this?.let {
        """{"pitch":${telemetryNumber(it.pitchDeg)},""" +
            """"roll":${telemetryNumber(it.rollDeg)},""" +
            """"yaw":${telemetryNumber(it.yawDeg)}}"""
    } ?: "null"

/** Velocity as `{x, y, z}` — the SDK's own names for north/east/down, or `null`. */
fun VelocityNedMps?.toWireJson(): String =
    this?.let {
        """{"x":${telemetryNumber(it.northMps)},""" +
            """"y":${telemetryNumber(it.eastMps)},""" +
            """"z":${telemetryNumber(it.downMps)}}"""
    } ?: "null"
