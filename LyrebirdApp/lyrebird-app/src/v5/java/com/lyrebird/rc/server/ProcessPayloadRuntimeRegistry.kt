package com.lyrebird.rc.server

import com.lyrebird.rc.models.PayloadWidgetVM

internal object ProcessPayloadRuntimeRegistry {
    private val payloadWidgetVM = PayloadWidgetVM()

    fun payloadWidgetVM(): PayloadWidgetVM = payloadWidgetVM
}
