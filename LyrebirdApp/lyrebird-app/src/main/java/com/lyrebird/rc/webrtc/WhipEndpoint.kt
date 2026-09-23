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
        // Any scheme, not just http(s): the field's hint is "host or host:port", so an operator
        // following MediaMTX's own documentation types ws:// and must not end up with a URL that
        // reads http://ws://host.
        var normalized =
            configuredServer
                .trim()
                .replace(SCHEME_PREFIX, "")
                .trimEnd('/')
        if (!normalized.contains(':')) normalized = "$normalized:$DEFAULT_PORT"
        return normalized
    }

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
