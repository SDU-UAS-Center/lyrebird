package com.lyrebird.rc.webrtc

internal object WhipEndpoint {
    private const val DEFAULT_PORT = 8889

    fun url(
        clientIp: String,
        droneName: String,
        configuredServer: String,
    ): String {
        val safeName = droneName.trim().ifEmpty { "lb_unknown" }
        val hostAndPort = normalizeHost(configuredServer, clientIp)
        return "http://$hostAndPort/$safeName/whip"
    }

    private fun normalizeHost(
        configuredServer: String,
        clientIp: String,
    ): String {
        if (configuredServer.isBlank()) return "$clientIp:$DEFAULT_PORT"
        var normalized =
            configuredServer
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')
        if (!normalized.contains(':')) normalized = "$normalized:$DEFAULT_PORT"
        return normalized
    }
}
