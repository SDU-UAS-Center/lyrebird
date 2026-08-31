package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MAVLink FTP v1 — the FILE_TRANSFER_PROTOCOL payload encoding, opcodes and errors. See
 * https://mavlink.io/en/services/ftp.html.
 *
 * The FILE_TRANSFER_PROTOCOL message (id 110) carries three target bytes (network/system/component)
 * followed by a 251-byte FTP payload; this object encodes and decodes that 251-byte payload only.
 *
 * Lyrebird serves the read-only subset — list the card, open a file for reading, read chunks,
 * terminate sessions. Every other client opcode is answered with NAK / UnknownCommand.
 *
 * Payload layout (all little-endian):
 *   seq_number(u16) session(u8) opcode(u8) size(u8) req_opcode(u8)
 *   burst_complete(u8) padding(u8) offset(u32) data[239]
 */
internal object MavlinkFtp {

    const val PAYLOAD_BYTES = 251
    const val HEADER_BYTES = 12
    const val DATA_BYTES = 239

    /** FILE_TRANSFER_PROTOCOL leads with target_network/system/component. */
    const val TARGET_BYTES = 3

    /** Full FILE_TRANSFER_PROTOCOL payload: three target bytes plus the FTP payload. */
    const val MESSAGE_PAYLOAD_BYTES = TARGET_BYTES + PAYLOAD_BYTES

    // Client opcodes the server implements.
    const val OP_NONE = 0
    const val OP_TERMINATE_SESSION = 1
    const val OP_RESET_SESSIONS = 2
    const val OP_LIST_DIRECTORY = 3
    const val OP_OPEN_FILE_RO = 4
    const val OP_READ_FILE = 5

    // Response opcodes.
    const val OP_ACK = 128
    const val OP_NAK = 129

    // MAV_FTP_ERR values.
    const val ERR_NONE = 0
    const val ERR_FAIL = 1
    const val ERR_INVALID_DATA_SIZE = 3
    const val ERR_INVALID_SESSION = 4
    const val ERR_NO_SESSIONS = 5
    const val ERR_EOF = 6
    const val ERR_UNKNOWN_COMMAND = 7
    const val ERR_FILE_NOT_FOUND = 10

    /** A decoded client request. */
    data class Request(
        val seq: Int,
        val session: Int,
        val opcode: Int,
        val size: Int,
        val offset: Long,
        val data: ByteArray
    ) {
        /** The request's path / filename, trimmed at the first NUL (the protocol's terminator). */
        fun text(): String {
            val end = data.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) data.size else it }
            return String(data, 0, end, Charsets.US_ASCII)
        }
    }

    /** Decode the 251-byte FTP payload into a request, or null when it is too short. */
    fun parse(payload: ByteArray): Request? {
        if (payload.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val seq = buffer.short.toInt() and 0xFFFF
        val session = buffer.get().toInt() and 0xFF
        val opcode = buffer.get().toInt() and 0xFF
        val size = buffer.get().toInt() and 0xFF
        buffer.get() // req_opcode: replies only
        buffer.get() // burst_complete
        buffer.get() // padding
        val offset = buffer.int.toLong() and 0xFFFFFFFFL
        val data = ByteArray(DATA_BYTES)
        buffer.get(data, 0, DATA_BYTES)
        return Request(seq, session, opcode, size, offset, data)
    }

    /**
     * An ACK reply. [data] is placed at the start of the 239-byte data field; [size] tells the
     * client how much of it is meaningful.
     */
    fun ack(
        seq: Int,
        session: Int,
        reqOpcode: Int,
        size: Int,
        offset: Long,
        data: ByteArray = EMPTY
    ): ByteArray =
        PayloadWriter(PAYLOAD_BYTES)
            .u16(seq)
            .u8(session)
            .u8(OP_ACK)
            .u8(size)
            .u8(reqOpcode)
            .u8(0) // burst_complete
            .u8(0) // padding
            .u32(offset)
            .bytes(data, DATA_BYTES)
            .build()

    /** A NAK reply; [error] is one of the MAV_FTP_ERR values, carried in data[0]. */
    fun nak(seq: Int, session: Int, reqOpcode: Int, error: Int): ByteArray =
        PayloadWriter(PAYLOAD_BYTES)
            .u16(seq)
            .u8(session)
            .u8(OP_NAK)
            .u8(1) // size: one byte of error information
            .u8(reqOpcode)
            .u8(0) // burst_complete
            .u8(0) // padding
            .u32(0)
            .bytes(byteArrayOf((error and 0xFF).toByte()), DATA_BYTES)
            .build()

    private val EMPTY = ByteArray(0)
}
