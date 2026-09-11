package com.lyrebird.rc.fleet

/**
 * A misconfiguration that only exists because more than one aircraft is on the network.
 *
 * Each of these is silent today. Nothing on any single device is wrong, so no device can detect
 * it alone, and the operator finds out from a downstream symptom that points nowhere near the
 * cause: one vehicle missing from QGroundControl, a video tile showing the wrong aircraft, a
 * ground station that can only see half the fleet. The mesh is the first thing in Lyrebird that
 * can see both sides of the clash, so it is the only thing that can name it.
 */
internal enum class ConflictKind {
    /**
     * Two aircraft advertising the same MAVLink system id.
     *
     * Automatic ids are a checksum of the aircraft serial folded into 155 slots, so a fleet of
     * five collides about six percent of the time. A ground station treats the pair as one
     * vehicle and silently drops the other.
     */
    DUPLICATE_SYSTEM_ID,

    /**
     * Two aircraft using the same drone name.
     *
     * The name is the identity every non-MAVLink surface keys on, including the discovery
     * response and the flight logs.
     */
    DUPLICATE_DRONE_NAME,

    /**
     * Two aircraft publishing video to the same MediaMTX path.
     *
     * The second publisher displaces the first, so one aircraft's tile shows the other's camera
     * and the loser simply disappears from the dashboard.
     */
    DUPLICATE_VIDEO_PATH,

    /**
     * The fleet does not agree on where MediaMTX lives.
     *
     * Usually one device left on a hand-typed address from a previous deployment. It publishes
     * to a server nobody is watching, which looks exactly like a video failure on that aircraft.
     */
    MISMATCHED_VIDEO_SERVER,

    /**
     * Two aircraft with home points close enough that their return paths overlap.
     *
     * Return-to-home is the one manoeuvre that runs without a pilot, on the aircraft's own
     * logic, and two aircraft returning into the same column of air at the same time is the
     * least supervised moment of the flight.
     */
    HOME_POINT_OVERLAP
}

internal enum class ConflictSeverity { INFO, WARNING, CRITICAL }

internal data class FleetConflict(
    val kind: ConflictKind,
    val severity: ConflictSeverity,
    /** One line, already written for the operator rather than for a log. */
    val summary: String,
    /** Drone names involved, own aircraft included, in display order. */
    val involved: List<String>
) {
    /** Very short label for the Flight Deck strip, where there is room for a word. */
    val shortLabel: String
        get() = when (kind) {
            ConflictKind.DUPLICATE_SYSTEM_ID -> "SYSID"
            ConflictKind.DUPLICATE_DRONE_NAME -> "NAME"
            ConflictKind.DUPLICATE_VIDEO_PATH -> "VIDEO"
            ConflictKind.MISMATCHED_VIDEO_SERVER -> "SERVER"
            ConflictKind.HOME_POINT_OVERLAP -> "HOME"
        }
}

/**
 * Compares the local device's identity against every live peer and names what clashes.
 *
 * Pure, and deliberately so: this is the part worth testing, and it should be exercisable
 * without a phone, a radio, or a second aircraft.
 */
internal object FleetConflicts {

    /** Home points closer than this share enough airspace that return paths can meet. */
    const val HOME_OVERLAP_RADIUS_M = 20.0

    /**
     * Every conflict visible from this device, most severe first.
     *
     * [own] is included in the comparison rather than treated as the reference, because the local
     * device is as likely to be the misconfigured one as any peer, and a warning that quietly
     * assumes otherwise sends the operator to the wrong RC.
     */
    fun detect(own: FleetBeacon, peers: List<FleetBeacon>): List<FleetConflict> {
        if (peers.isEmpty()) return emptyList()
        val everyone = listOf(own) + peers.filter { it.deviceId != own.deviceId }
        val conflicts = mutableListOf<FleetConflict>()
        conflicts += duplicateSystemIds(everyone)
        conflicts += duplicateDroneNames(everyone)
        conflicts += duplicateVideoPaths(everyone)
        conflicts += mismatchedVideoServers(everyone)
        conflicts += overlappingHomePoints(everyone)
        return conflicts.sortedByDescending { it.severity.ordinal }
    }

    private fun duplicateSystemIds(everyone: List<FleetBeacon>): List<FleetConflict> =
        everyone.filter { it.systemId > 0 }
            .groupBy { it.systemId }
            .filterValues { it.size > 1 }
            .map { (systemId, clashing) ->
                FleetConflict(
                    kind = ConflictKind.DUPLICATE_SYSTEM_ID,
                    severity = ConflictSeverity.CRITICAL,
                    summary = "MAVLink ID $systemId is claimed by ${nameList(clashing)}. " +
                        "A ground station will see them as one vehicle.",
                    involved = clashing.map { it.displayName() }
                )
            }

    private fun duplicateDroneNames(everyone: List<FleetBeacon>): List<FleetConflict> =
        everyone.filter { it.droneName.isNotBlank() }
            .groupBy { it.droneName.trim().lowercase() }
            .filterValues { it.size > 1 }
            .map { (_, clashing) ->
                FleetConflict(
                    kind = ConflictKind.DUPLICATE_DRONE_NAME,
                    severity = ConflictSeverity.CRITICAL,
                    summary = "${clashing.size} aircraft are named " +
                        "\"${clashing.first().droneName}\". Rename all but one.",
                    involved = clashing.map { it.displayName() }
                )
            }

    /**
     * The video path is derived from the drone name today, so a name clash usually implies a path
     * clash. It is still checked separately: they are different failures with different
     * consequences, and nothing guarantees the derivation stays the same.
     */
    private fun duplicateVideoPaths(everyone: List<FleetBeacon>): List<FleetConflict> =
        everyone.filter { it.videoPath.isNotBlank() }
            .groupBy { it.videoPath.trim().lowercase() }
            .filterValues { it.size > 1 }
            .map { (path, clashing) ->
                FleetConflict(
                    kind = ConflictKind.DUPLICATE_VIDEO_PATH,
                    severity = ConflictSeverity.WARNING,
                    summary = "${nameList(clashing)} all publish video to \"$path\". " +
                        "Only the last one to connect will be visible.",
                    involved = clashing.map { it.displayName() }
                )
            }

    /**
     * Only devices with an explicit server are compared. A blank value means "resolve it from
     * whoever connects", which is correct on every device at once and never a disagreement.
     */
    private fun mismatchedVideoServers(everyone: List<FleetBeacon>): List<FleetConflict> {
        val configured = everyone.filter { it.videoServer.isNotBlank() }
        val distinct = configured.map { it.videoServer.trim().lowercase() }.distinct()
        if (distinct.size < 2) return emptyList()
        return listOf(
            FleetConflict(
                kind = ConflictKind.MISMATCHED_VIDEO_SERVER,
                severity = ConflictSeverity.WARNING,
                summary = "The fleet points at ${distinct.size} different video servers " +
                    "(${distinct.joinToString(", ")}). Push one profile to align them.",
                involved = configured.map { it.displayName() }
            )
        )
    }

    private fun overlappingHomePoints(everyone: List<FleetBeacon>): List<FleetConflict> {
        val withHome = everyone.filter { it.hasRealHome() }
        val conflicts = mutableListOf<FleetConflict>()
        for (first in withHome.indices) {
            for (second in first + 1 until withHome.size) {
                val a = withHome[first]
                val b = withHome[second]
                val separationM = FleetGeo.horizontalDistanceM(
                    a.homeLatitudeDeg, a.homeLongitudeDeg, b.homeLatitudeDeg, b.homeLongitudeDeg
                )
                if (separationM > HOME_OVERLAP_RADIUS_M) continue
                conflicts += FleetConflict(
                    kind = ConflictKind.HOME_POINT_OVERLAP,
                    severity = ConflictSeverity.INFO,
                    summary = "${a.displayName()} and ${b.displayName()} have home points " +
                        "${separationM.toInt()}m apart. Their return paths overlap.",
                    involved = listOf(a.displayName(), b.displayName())
                )
            }
        }
        return conflicts
    }

    private fun nameList(beacons: List<FleetBeacon>): String =
        beacons.joinToString(" and ") { it.displayName() }
}

internal fun FleetBeacon.displayName(): String =
    droneName.trim().ifEmpty { deviceId.takeLast(SHORT_ID_LENGTH) }

private const val SHORT_ID_LENGTH = 6
