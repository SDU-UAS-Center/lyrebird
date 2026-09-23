package com.lyrebird.rc.webrtc

import com.lyrebird.rc.settings.DroneSettingsProfilesTest
import com.lyrebird.rc.settings.LyrebirdSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The runtime-owned native streaming configuration: values come from the preference store, so
 * they survive the screen that started the stream, and the UI hears about the streamer only
 * through callbacks. This replaced a weak host that degraded to empty credentials once the
 * activity was gone.
 */
class V5SettingsNativeStreamingHostTest {
    private val statuses = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var configChanges = 0

    private fun hostWith(
        prefs: Map<String, Any> = emptyMap(),
    ): Pair<V5SettingsNativeStreamingHost, DroneSettingsProfilesTest.FakePrefs> {
        val preferences = DroneSettingsProfilesTest.FakePrefs(prefs)
        val host =
            V5SettingsNativeStreamingHost(
                preferences = preferences,
                onStatus = { statuses += it },
                onMessage = { messages += it },
                onConfigChanged = { configChanges++ },
            )
        return host to preferences
    }

    @Test
    fun `a stored RTMP URL wins over the fallback`() {
        val (host, _) = hostWith(mapOf(LyrebirdSettings.PREF_RTMP_URL to "rtmp://farm.example/live/lb"))

        assertEquals("rtmp://farm.example/live/lb", host.rtmpUrl("10.9.0.2"))
    }

    @Test
    fun `the fallback names the aircraft from preferences`() {
        val (host, _) = hostWith(mapOf(LyrebirdSettings.PREF_DRONE_NAME to "lyrebird-7"))

        assertEquals("rtmp://10.9.0.2:1935/lyrebird-7", host.rtmpUrl("10.9.0.2"))
    }

    @Test
    fun `an unset aircraft name falls back to the default`() {
        val (host, _) = hostWith()

        assertEquals(
            "rtmp://10.9.0.2:1935/${LyrebirdSettings.DEFAULT_DRONE_NAME}",
            host.rtmpUrl("10.9.0.2"),
        )
    }

    @Test
    fun `credentials and ports come from preferences`() {
        val (host, _) =
            hostWith(
                mapOf(
                    LyrebirdSettings.PREF_RTSP_PORT to 8555,
                    LyrebirdSettings.PREF_RTSP_USER to "pilot",
                    LyrebirdSettings.PREF_RTSP_PWD to "hunter2",
                    LyrebirdSettings.PREF_AGORA_CHANNEL to "ch1",
                    LyrebirdSettings.PREF_GB_SERVER_IP to "10.0.0.9",
                ),
            )

        assertEquals(8555, host.rtspPort())
        assertEquals("pilot", host.rtspUsername())
        assertEquals("hunter2", host.rtspPassword())
        assertEquals("ch1", host.agoraChannel())
        assertEquals("10.0.0.9", host.gbServerIp())
    }

    @Test
    fun `a write goes through to the preference store`() {
        val (host, preferences) = hostWith()

        host.setRtmpUrl("rtmp://new.example/live")
        host.setRtspPort(18554)

        assertEquals("rtmp://new.example/live", preferences.getString(LyrebirdSettings.PREF_RTMP_URL, null))
        assertEquals(18554, preferences.getInt(LyrebirdSettings.PREF_RTSP_PORT, 0))
    }

    @Test
    fun `the streamer's events reach the UI only through the callbacks`() {
        val (host, _) = hostWith()

        host.onStatus("rtmp-connecting")
        host.onMessage("RTMP started")
        host.onConfigChanged()

        assertEquals(listOf("rtmp-connecting"), statuses)
        assertEquals(listOf("RTMP started"), messages)
        assertEquals(1, configChanges)
    }
}
