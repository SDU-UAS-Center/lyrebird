package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAVLink FTP v1 protocol + server, against a fake file source. The server's blocking work is run
 * inline (an executor that runs the task on the calling thread), so every reply is deterministic.
 */
class MavlinkFtpTest {

    private val source = object : MavlinkFtpServer.FtpFileSource {
        override fun listFiles(): List<Pair<String, Long>> =
            (1..30).map { "DJI_%04d.JPG".format(it) to (it * 100L) }

        override fun readFileBytes(name: String): ByteArray? = when (name) {
            "DJI_0001.JPG" -> ByteArray(100) { it.toByte() }
            "DJI_0002.JPG" -> ByteArray(200) { (it % 251).toByte() }
            else -> null
        }
    }

    /** One server per test: sessions opened by one handle() call must survive the next. */
    private val server = MavlinkFtpServer(source) { it.run() }

    private fun request(
        seq: Int,
        session: Int,
        opcode: Int,
        size: Int,
        offset: Long = 0,
        data: ByteArray = ByteArray(0)
    ): MavlinkFtp.Request =
        checkNotNull(
            MavlinkFtp.parse(
                PayloadWriter(MavlinkFtp.PAYLOAD_BYTES)
                    .u16(seq)
                    .u8(session)
                    .u8(opcode)
                    .u8(size)
                    .u8(0) // req_opcode
                    .u8(0) // burst
                    .u8(0) // padding
                    .u32(offset)
                    .bytes(data, MavlinkFtp.DATA_BYTES)
                    .build()
            )
        )

    private fun handle(req: MavlinkFtp.Request): Reply {
        val replies = mutableListOf<ByteArray>()
        server.handle(req) { replies.add(it) }
        assertEquals("exactly one reply", 1, replies.size)
        return Reply(replies.single())
    }

    private data class Reply(
        val seq: Int,
        val session: Int,
        val opcode: Int,
        val size: Int,
        val reqOpcode: Int,
        val offset: Long,
        val data: ByteArray
    ) {
        val error: Int get() = if (opcode == MavlinkFtp.OP_NAK) data[0].toInt() and 0xFF else -1
        val text: String get() = String(data, 0, size, Charsets.US_ASCII)
        val u32le: Long get() = (data[0].toLong() and 0xFF) or
            ((data[1].toLong() and 0xFF) shl 8) or
            ((data[2].toLong() and 0xFF) shl 16) or
            ((data[3].toLong() and 0xFF) shl 24)
    }

    private fun Reply(payload: ByteArray): Reply {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val seq = buffer.short.toInt() and 0xFFFF
        val session = buffer.get().toInt() and 0xFF
        val opcode = buffer.get().toInt() and 0xFF
        val size = buffer.get().toInt() and 0xFF
        val reqOpcode = buffer.get().toInt() and 0xFF
        buffer.get() // burst_complete
        buffer.get() // padding
        val offset = buffer.int.toLong() and 0xFFFFFFFFL
        val data = ByteArray(239).also { buffer.get(it) }
        return Reply(seq, session, opcode, size, reqOpcode, offset, data)
    }

    @Test
    fun aRequestRoundTripsThroughTheWireFormat() {
        val parsed = MavlinkFtp.parse(
            PayloadWriter(MavlinkFtp.PAYLOAD_BYTES)
                .u16(0x1234)
                .u8(7)
                .u8(MavlinkFtp.OP_READ_FILE)
                .u8(64)
                .u8(0)
                .u8(1)
                .u8(0)
                .u32(4096)
                .bytes("DJI_0001.JPG".toByteArray(), MavlinkFtp.DATA_BYTES)
                .build()
        )!!
        assertEquals(0x1234, parsed.seq)
        assertEquals(7, parsed.session)
        assertEquals(MavlinkFtp.OP_READ_FILE, parsed.opcode)
        assertEquals(64, parsed.size)
        assertEquals(4096L, parsed.offset)
        assertEquals("DJI_0001.JPG", parsed.text())
    }

    @Test
    fun anUnknownOpcodeIsNakedWithUnknownCommand() {
        val reply = handle(request(1, 0, 99, 0))
        assertEquals(MavlinkFtp.OP_NAK, reply.opcode)
        assertEquals(MavlinkFtp.ERR_UNKNOWN_COMMAND, reply.error)
        assertEquals(1, reply.seq)
        assertEquals(99, reply.reqOpcode)
    }

    @Test
    fun listDirectoryPagesByOffsetAndEndsWithEof() {
        val first = handle(request(1, 0, MavlinkFtp.OP_LIST_DIRECTORY, 0, offset = 0))
        assertEquals(MavlinkFtp.OP_ACK, first.opcode)
        assertTrue("a page of entries", first.size > 0)
        assertTrue("entries start with the F marker", first.text.startsWith("FDJI_"))
        assertTrue("entries are NUL-separated", first.text.endsWith("\u0000"))
        assertTrue("page fits the data field", first.size <= MavlinkFtp.DATA_BYTES)

        // Page through all 30 entries; each page returns at least one entry so this terminates.
        var offset = first.text.count { it == '\u0000' }
        var pages = 1
        while (offset < 30) {
            val next = handle(request(1, 0, MavlinkFtp.OP_LIST_DIRECTORY, 0, offset = offset.toLong()))
            if (next.opcode == MavlinkFtp.OP_NAK) {
                assertEquals(MavlinkFtp.ERR_EOF, next.error)
                break
            }
            offset += next.text.count { it == '\u0000' }
            pages++
        }
        assertTrue("paged through the whole listing", pages >= 1 && pages <= 30)
        // Past the end is EOF.
        val past = handle(request(1, 0, MavlinkFtp.OP_LIST_DIRECTORY, 0, offset = 30))
        assertEquals(MavlinkFtp.OP_NAK, past.opcode)
        assertEquals(MavlinkFtp.ERR_EOF, past.error)
    }

    @Test
    fun openReturnsASessionAndTheFileSize() {
        val reply = handle(
            request(1, 0, MavlinkFtp.OP_OPEN_FILE_RO, 12, data = "DJI_0001.JPG".toByteArray())
        )
        assertEquals(MavlinkFtp.OP_ACK, reply.opcode)
        assertEquals(4, reply.size)
        assertEquals(100L, reply.u32le)
        assertTrue("a session is allocated", reply.session in 1..255)
    }

    @Test
    fun openingAMissingFileIsNakedWithFileNotFound() {
        val reply = handle(
            request(1, 0, MavlinkFtp.OP_OPEN_FILE_RO, 11, data = "NOPE.JPG".toByteArray())
        )
        assertEquals(MavlinkFtp.OP_NAK, reply.opcode)
        assertEquals(MavlinkFtp.ERR_FILE_NOT_FOUND, reply.error)
    }

    @Test
    fun readServesChunksThenEof() {
        val opened = handle(
            request(1, 0, MavlinkFtp.OP_OPEN_FILE_RO, 12, data = "DJI_0002.JPG".toByteArray())
        )
        val session = opened.session

        val head = handle(request(2, session, MavlinkFtp.OP_READ_FILE, 50, offset = 0))
        assertEquals(MavlinkFtp.OP_ACK, head.opcode)
        assertEquals(50, head.size)
        assertEquals(0L, head.offset)
        assertEquals(0, head.data[0].toInt()) // byte 0 of the pattern

        val short = handle(request(3, session, MavlinkFtp.OP_READ_FILE, 239, offset = 190))
        assertEquals(MavlinkFtp.OP_ACK, short.opcode)
        assertEquals(10, short.size) // only 10 bytes left

        val past = handle(request(4, session, MavlinkFtp.OP_READ_FILE, 239, offset = 200))
        assertEquals(MavlinkFtp.OP_NAK, past.opcode)
        assertEquals(MavlinkFtp.ERR_EOF, past.error)
    }

    @Test
    fun readingAnUnknownSessionIsNakedWithInvalidSession() {
        val reply = handle(request(1, 42, MavlinkFtp.OP_READ_FILE, 10, offset = 0))
        assertEquals(MavlinkFtp.OP_NAK, reply.opcode)
        assertEquals(MavlinkFtp.ERR_INVALID_SESSION, reply.error)
    }

    @Test
    fun terminateClosesTheSessionAndResetClearsEverything() {
        val opened = handle(
            request(1, 0, MavlinkFtp.OP_OPEN_FILE_RO, 12, data = "DJI_0001.JPG".toByteArray())
        )
        val session = opened.session

        val terminated = handle(request(2, session, MavlinkFtp.OP_TERMINATE_SESSION, 0))
        assertEquals(MavlinkFtp.OP_ACK, terminated.opcode)

        val after = handle(request(3, session, MavlinkFtp.OP_READ_FILE, 10, offset = 0))
        assertEquals(MavlinkFtp.OP_NAK, after.opcode)
        assertEquals(MavlinkFtp.ERR_INVALID_SESSION, after.error)

        val opened2 = handle(
            request(4, 0, MavlinkFtp.OP_OPEN_FILE_RO, 12, data = "DJI_0001.JPG".toByteArray())
        )
        val reset = handle(request(5, 0, MavlinkFtp.OP_RESET_SESSIONS, 0))
        assertEquals(MavlinkFtp.OP_ACK, reset.opcode)

        val afterReset = handle(request(6, opened2.session, MavlinkFtp.OP_READ_FILE, 10, offset = 0))
        assertEquals(MavlinkFtp.OP_NAK, afterReset.opcode)
        assertEquals(MavlinkFtp.ERR_INVALID_SESSION, afterReset.error)
    }

    @Test
    fun concurrentOpensOfTheSameFileDownloadItOnce() {
        // A client that retries a slow open must not start a second full download of the same
        // file: the second open waits on the first one's in-flight download.
        val started = CountDownLatch(1)
        val calls = AtomicInteger()
        val blockingSource = object : MavlinkFtpServer.FtpFileSource {
            override fun listFiles(): List<Pair<String, Long>> = emptyList()
            override fun readFileBytes(name: String): ByteArray? {
                calls.incrementAndGet()
                started.countDown()
                Thread.sleep(300)
                return ByteArray(100) { it.toByte() }
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        val server = MavlinkFtpServer(blockingSource, executor)
        val replies = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

        server.handle(
            request(1, 0, MavlinkFtp.OP_OPEN_FILE_RO, 6, data = "A.JPG".toByteArray())
        ) { replies.add(it) }
        server.handle(
            request(2, 0, MavlinkFtp.OP_OPEN_FILE_RO, 6, data = "A.JPG".toByteArray())
        ) { replies.add(it) }

        assertTrue("the single download starts", started.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (replies.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        executor.shutdownNow()

        assertEquals("both opens are answered", 2, replies.size)
        assertEquals("the file is downloaded once", 1, calls.get())
    }
}
