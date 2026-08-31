package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only MAVLink FTP v1 server: list the SD card, open a file for reading, read chunks.
 *
 * Every client command arrives inside a FILE_TRANSFER_PROTOCOL message and is answered with an
 * ACK (or a NAK carrying a MAV_FTP_ERR). ListDirectory and OpenFileRO are dispatched to [executor]
 * because they pull the media list / download a file from the camera — both blocking — and must
 * not stall the MAVLink receive thread. ReadFile and session teardown are served inline from the
 * in-memory copy made at open.
 *
 * Only one session is needed per client in practice (QGC uses a single session id); the id is
 * still allocated here rather than hard-coded so two clients cannot collide.
 */
internal class MavlinkFtpServer(
    private val files: FtpFileSource,
    private val executor: Executor
) {
    /** Where file bytes come from. Implemented by the host activity against the DJI media API. */
    interface FtpFileSource {
        /** Every downloadable file, as name to size in bytes. */
        fun listFiles(): List<Pair<String, Long>>

        /** Read one whole file into memory, or null when it cannot be found or downloaded. */
        fun readFileBytes(name: String): ByteArray?
    }

    private data class Session(val name: String, val bytes: ByteArray, val size: Long)

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextSession = AtomicInteger(1)

    /**
     * Downloads in flight, keyed by file name. Opening the same file twice — a client that
     * retries a slow open, or two clients browsing at once — must not start a second full
     * download of the same bytes; the second open waits on the first one's result.
     */
    private val inflight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    /**
     * The thread that actually pulls bytes off the camera. Deliberately separate from [executor]:
     * an open blocks waiting on its download, so if the download ran on the same pool the open's
     * thread would be unavailable to start it — two concurrent opens would exhaust the pool and
     * deadlock. A single downloader serialises the camera (one fetch at a time is also the
     * politest way to treat a slow SD-card interface).
     */
    private val downloadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mavlink-ftp-download").apply { isDaemon = true }
    }

    /** Stop the download thread. The request [executor] is owned by the caller. */
    fun shutdown() {
        downloadExecutor.shutdownNow()
    }

    /** Handle one request; [reply] may be invoked from a worker after a blocking media call. */
    fun handle(request: MavlinkFtp.Request, reply: (ByteArray) -> Unit) {
        when (request.opcode) {
            MavlinkFtp.OP_TERMINATE_SESSION -> {
                sessions.remove(request.session)
                reply(MavlinkFtp.ack(request.seq, request.session, request.opcode, 0, 0))
            }

            MavlinkFtp.OP_RESET_SESSIONS -> {
                sessions.clear()
                reply(MavlinkFtp.ack(request.seq, 0, request.opcode, 0, 0))
            }

            MavlinkFtp.OP_LIST_DIRECTORY -> executor.execute { reply(buildListing(request)) }

            MavlinkFtp.OP_OPEN_FILE_RO -> executor.execute { reply(openFile(request)) }

            MavlinkFtp.OP_READ_FILE -> reply(readFile(request))

            else -> reply(
                MavlinkFtp.nak(request.seq, request.session, request.opcode, MavlinkFtp.ERR_UNKNOWN_COMMAND)
            )
        }
    }

    private fun openFile(request: MavlinkFtp.Request): ByteArray {
        val name = request.text()
        if (name.isEmpty()) {
            return MavlinkFtp.nak(request.seq, 0, request.opcode, MavlinkFtp.ERR_FILE_NOT_FOUND)
        }
        val bytes = downloadOnce(name)
        if (bytes == null) {
            return MavlinkFtp.nak(request.seq, 0, request.opcode, MavlinkFtp.ERR_FILE_NOT_FOUND)
        }
        val session = nextSession.getAndIncrement() and 0xFF
        sessions[session] = Session(name, bytes, bytes.size.toLong())
        // ACK: session in the header, size=4, data = the opened file's size as u32 LE.
        val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array()
        return MavlinkFtp.ack(request.seq, session, request.opcode, 4, 0, size)
    }

    /** Download [name] once; concurrent opens of the same name share the in-flight download. */
    private fun downloadOnce(name: String): ByteArray? {
        val future = inflight.computeIfAbsent(name) {
            CompletableFuture<ByteArray?>().also { created ->
                downloadExecutor.execute { created.complete(files.readFileBytes(name)) }
            }
        }
        val bytes = try {
            future.get()
        } catch (e: Exception) {
            null
        }
        inflight.remove(name, future)
        return bytes
    }

    private fun readFile(request: MavlinkFtp.Request): ByteArray {
        val session = sessions[request.session]
            ?: return MavlinkFtp.nak(
                request.seq, request.session, request.opcode, MavlinkFtp.ERR_INVALID_SESSION
            )
        val offset = request.offset
        if (offset >= session.size) {
            return MavlinkFtp.nak(request.seq, request.session, request.opcode, MavlinkFtp.ERR_EOF)
        }
        val count = minOf(request.size.coerceAtMost(MavlinkFtp.DATA_BYTES), (session.size - offset).toInt())
        val chunk = session.bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        return MavlinkFtp.ack(request.seq, request.session, request.opcode, count, offset, chunk)
    }

    /**
     * List entries starting at index [request.offset], as many as fit in the data field. Each
     * entry is `F<name>\t<size>\0`; the client pages with increasing offsets and the listing ends
     * when an offset beyond the last entry is NAKed with EOF.
     */
    private fun buildListing(request: MavlinkFtp.Request): ByteArray {
        val entries = files.listFiles()
        val start = request.offset.toInt()
        if (start >= entries.size) {
            return MavlinkFtp.nak(request.seq, request.session, request.opcode, MavlinkFtp.ERR_EOF)
        }
        val out = java.io.ByteArrayOutputStream()
        var index = start
        while (index < entries.size && out.size() < MavlinkFtp.DATA_BYTES) {
            val (name, size) = entries[index]
            // Tab is the field separator, so names containing one are skipped; camera-generated
            // DCF names never contain a tab.
            if (name.contains('\t')) {
                index++
                continue
            }
            val entry = "F$name\t$size\u0000".toByteArray(Charsets.US_ASCII)
            if (out.size() + entry.size > MavlinkFtp.DATA_BYTES) break
            out.write(entry)
            index++
        }
        val data = out.toByteArray()
        return MavlinkFtp.ack(request.seq, request.session, request.opcode, data.size, start.toLong(), data)
    }
}
