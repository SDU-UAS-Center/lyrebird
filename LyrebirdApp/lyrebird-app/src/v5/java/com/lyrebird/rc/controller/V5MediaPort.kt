package com.lyrebird.rc.controller

import com.lyrebird.rc.LyrebirdMediaPort
import com.lyrebird.rc.models.MediaVM
import java.io.OutputStream

internal class V5MediaPort(
    private val mediaProvider: () -> MediaVM,
) : LyrebirdMediaPort {
    override fun capturePhotoFileName(): String? = Payload.capturePhoto(mediaProvider())?.fileName

    override fun captureThermalJson(): String? = Payload.captureThermal(mediaProvider())

    override fun listMediaJson(): String = Payload.listAllMedia(mediaProvider())

    override fun sendMediaFile(
        fileName: String,
        outputStream: OutputStream,
    ) {
        Payload.sendMediaFileByName(mediaProvider(), fileName, outputStream)
    }

    override fun sendErrorResponse(
        message: String,
        outputStream: OutputStream,
    ) {
        Payload.sendErrorResponse(outputStream, message)
    }
}
