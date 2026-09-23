package com.lyrebird.rc.webrtc

/**
 * Who a decoded frame goes to, and the reference arithmetic that handing it over requires.
 *
 * A frame source has one consumer on the ordinary path and several when a second viewer, a
 * recorder or a detector joins. Those two cases are deliberately different shapes: one consumer
 * takes the frame the source built, while several share one buffer and each releases the reference
 * it was given — so the source must retain one extra reference per consumer beyond the first.
 * Getting that count wrong leaks the buffer (too many) or frees it under a consumer's feet (too
 * few), and both show up as a crash in a native library rather than as a failing test.
 *
 * So the arithmetic lives here, where it can be tested with a fake frame, instead of inside an SDK
 * callback where the only way to check it is to fly. The snapshot is taken once per frame and
 * reused, and the single-consumer case stays allocation-free because that is the one every flight
 * runs.
 */
internal class FrameRecipients<R : Any> private constructor(
    private val lone: R?,
    private val all: List<R>,
) {
    /** How many consumers this frame has. Zero means the frame is never built. */
    val count: Int = if (lone != null) 1 else all.size

    val isEmpty: Boolean get() = count == 0

    /**
     * Hand [frame] to every consumer.
     *
     * [retain] is called once per consumer after the first, and [hand] once per consumer: the
     * caller keeps its own reference and releases it afterwards, so the reference count ends where
     * it started however many consumers there were.
     */
    fun <F : Any> deliver(
        frame: F,
        retain: (F) -> Unit,
        hand: (R, F) -> Unit,
    ) {
        val only = lone
        if (only != null) {
            hand(only, frame)
            return
        }
        repeat((all.size - 1).coerceAtLeast(0)) { retain(frame) }
        all.forEach { hand(it, frame) }
    }

    /** Hand something that is not reference-counted (metadata) to every consumer. */
    fun forEach(hand: (R) -> Unit) {
        val only = lone
        if (only != null) {
            hand(only)
            return
        }
        all.forEach(hand)
    }

    companion object {
        fun <R : Any> none(): FrameRecipients<R> = FrameRecipients(null, emptyList())

        fun <R : Any> lone(value: R): FrameRecipients<R> = FrameRecipients(value, emptyList())

        fun <R : Any> many(values: List<R>): FrameRecipients<R> = FrameRecipients(null, values)

        /**
         * Snapshot a live consumer collection (a concurrent map's `values`, in practice).
         *
         * One consumer is held as a field rather than copied into a list: this runs on the
         * camera's callback thread for every frame, and the common case is one viewer.
         */
        fun <R : Any> of(values: Collection<R>): FrameRecipients<R> =
            when (values.size) {
                0 -> none()
                1 -> lone(values.first())
                else -> many(values.toList())
            }
    }
}
