package com.lyrebird.rc.fleet

/**
 * Identity colours for the fleet.
 *
 * Every aircraft gets one colour, the same colour on every RC, and that colour is what its name is
 * drawn in on the strip and what its chevron is filled with on the map. Nothing else has to match:
 * a pilot glances at a row, then at the map, and the two agree without reading either.
 *
 * Colour used to carry state instead, which made two aircraft with the same advisory look like the
 * same aircraft and left a column of four rows with nothing in it to tell one from another. State
 * has not gone anywhere - the dot on each row and the ring around each chevron still carry it - but
 * the thing a pilot has to *match* now has a colour, and the thing they read has the position.
 *
 * The colour is hashed from the drone's name rather than from its device id, because the name is
 * the stable half of an aircraft's identity. A device id is the aircraft serial once the aircraft
 * has linked and an installation id until it does, so a peer that connects (or drops) mid-session
 * changes it - and a colour keyed on it changed with it, on a strip where nothing else had moved.
 * The name is also the thing drawn on screen, so colouring by it means the colour cannot disagree
 * with the label it is colouring. Two aircraft sharing a name is already a fleet conflict the app
 * reports, so a shared colour in that case is a symptom of a real problem rather than a new one.
 */
internal object FleetPalette {
    /**
     * A member as the assignment sees it: keyed by device id, coloured by name.
     *
     * The id is the key because that is what a roster entry is keyed by; the name is the identity
     * because that is what survives an aircraft connecting.
     */
    data class Member(
        val deviceId: String,
        val name: String,
    ) {
        /** What the colour is actually hashed from: the name, or the id for a nameless device. */
        val colourKey: String get() = name.trim().lowercase().ifEmpty { deviceId }
    }

    /**
     * Hues kept apart from each other, and from the alert colours, on a dark background.
     *
     * Red, amber and brown are missing on purpose: those mean warning, caution and lost everywhere
     * else in this app, and an aircraft permanently drawn in one of them would be an aircraft
     * nobody looked at.
     */
    private val COLORS =
        intArrayOf(
            0xFF4FC3F7.toInt(), // light blue
            0xFF81C784.toInt(), // green
            0xFFBA68C8.toInt(), // purple
            0xFF4DD0E1.toInt(), // cyan
            0xFFF06292.toInt(), // pink
            0xFFAED581.toInt(), // light green
            0xFF9575CD.toInt(), // deep purple
            0xFF4DB6AC.toInt(), // teal
            0xFF64B5F6.toInt(), // blue
            0xFFDCE775.toInt(), // lime
            0xFFE1BEE7.toInt(), // pale violet
            0xFF80CBC4.toInt(), // pale teal
        )

    /** What a member with no colour of its own falls back to, rather than crashing a readout. */
    val UNKNOWN = 0xFFECEFF1.toInt()

    /** How many members can be on screen at once before colours have to repeat. */
    val capacity: Int get() = COLORS.size

    /**
     * One colour per member: this aircraft first, then the peers in a canonical order.
     *
     * A hash collision moves the later member to the next free colour, and only for as long as the
     * two are both on the list, so two aircraft on screen are never drawn alike. The order is the
     * sorted colour keys rather than the order they appeared, so the assignment depends on who is
     * on the list and not on which beacon arrived first.
     */
    fun assign(
        self: Member?,
        peers: List<Member>,
    ): Map<String, Int> {
        val assigned = LinkedHashMap<String, Int>()
        val taken = mutableSetOf<Int>()
        self?.let { member -> assigned[member.deviceId] = take(COLORS, taken, member.colourKey) }
        peers
            .sortedBy { it.colourKey }
            .forEach { member -> assigned[member.deviceId] = take(COLORS, taken, member.colourKey) }
        return assigned
    }

    /**
     * The first free colour at or after the key's own, so collisions resolve the same way twice.
     *
     * A fleet larger than the palette is a fleet with more aircraft on one network than the app can
     * tell apart by colour, which is a real possibility and not an error: past that point each
     * member keeps its own colour and starts sharing it with somebody else, which is still better
     * than the last few all being drawn in the same "no colour" grey.
     */
    private fun take(
        palette: IntArray,
        taken: MutableSet<Int>,
        colourKey: String,
    ): Int {
        val own = Math.floorMod(colourKey.hashCode(), palette.size)
        var index = own
        var attempts = 0
        while (attempts < palette.size) {
            if (taken.add(palette[index])) return palette[index]
            index = (index + 1) % palette.size
            attempts++
        }
        return palette[own]
    }

    /** The same hue at a different opacity, for a marker that is a memory rather than a position. */
    fun dimmed(
        color: Int,
        alpha: Int,
    ): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
