import threading
from typing import ClassVar

from lyrebird_groundstation import dji_client
from lyrebird_groundstation.dji_client import DJIInterface
from lyrebird_groundstation.transport import TCP_GAP_MODE_REQUEST, Transport


class RecordingDJIInterface(DJIInterface):
    def __init__(self):
        self.calls = []
        super().__init__("192.168.1.42")

    def requestSend(self, endPoint, data, verbose=False):  # noqa: N802, N803
        self.calls.append((endPoint, data, verbose))
        return "ok"


def test_client_uses_string_discovery_result():
    client = DJIInterface("", discover_callback=lambda: "192.168.1.42")

    assert client.IP_RC == "192.168.1.42"
    assert client.drone_name == "UNKNOWN"
    assert client.baseCommandUrl == "http://192.168.1.42:8080"


def test_client_uses_tuple_discovery_result_and_name():
    client = DJIInterface("", discover_callback=lambda: ("192.168.1.42", "mini1"))

    assert client.IP_RC == "192.168.1.42"
    assert client.drone_name == "mini1"


def test_client_can_query_config_name_for_known_ip():
    client = DJIInterface(
        "192.168.1.42",
        config_loader=lambda ip: {"droneName": f"name-for-{ip}"},
        query_config_name=True,
    )

    assert client.drone_name == "name-for-192.168.1.42"


def test_process_telemetry_data_stores_latest_complete_item():
    client = DJIInterface("192.168.1.42", timestamp_factory=lambda: "t1")

    buffer = client._process_telemetry_data(
        '{"heading": 1',
        b'}\nnot-json\n{"batteryLevel": 88}\n{"partial": true',
    )

    assert buffer == '{"partial": true'
    assert client.getTelemetry() == {"batteryLevel": 88, "timestamp": "t1"}


def test_gap_marked_telemetry_merges_instead_of_replacing():
    """A trimmed ``telemetryMode: gap`` payload must not erase state a full one already set.

    This is what lets ``both`` transport skip re-sending fields MAVLink already carries: the
    aircraft sends the full snapshot once, then only gap payloads, and the client's picture of
    the drone must keep the full snapshot's fields in between.
    """
    client = DJIInterface("192.168.1.42", timestamp_factory=lambda: "t1")

    client._process_telemetry_data("", b'{"batteryLevel": 91, "location": {"latitude": 1}}\n')
    client._process_telemetry_data("", b'{"telemetryMode": "gap", "zoomRatio": 2.0}\n')

    telemetry = client.getTelemetry()
    assert telemetry["batteryLevel"] == 91
    assert telemetry["zoomRatio"] == 2.0


def test_full_telemetry_still_replaces_stale_gap_state():
    """The inverse of the merge above: a real full snapshot is authoritative and wins outright.

    Without this, a field a full snapshot stopped reporting could linger forever from an older
    gap payload, since merging never removes a key.
    """
    client = DJIInterface("192.168.1.42", timestamp_factory=lambda: "t1")

    client._process_telemetry_data("", b'{"telemetryMode": "gap", "zoomRatio": 2.0}\n')
    client._process_telemetry_data("", b'{"batteryLevel": 91}\n')

    telemetry = client.getTelemetry()
    assert telemetry == {"batteryLevel": 91, "timestamp": "t1"}


class _FakeTelemetrySocket:
    def __init__(self, *_args, **_kwargs):
        self.sent: list[bytes] = []

    def settimeout(self, _value):
        pass

    def connect(self, _address):
        pass

    def sendall(self, data):
        self.sent.append(data)


def _patch_fake_telemetry_socket(monkeypatch) -> list[_FakeTelemetrySocket]:
    sockets: list[_FakeTelemetrySocket] = []

    def fake_socket_factory(*_args, **_kwargs):
        sockets.append(_FakeTelemetrySocket())
        return sockets[-1]

    monkeypatch.setattr(dji_client.socket, "socket", fake_socket_factory)
    return sockets


def test_both_transport_requests_gap_mode_telemetry_on_connect(monkeypatch):
    sockets = _patch_fake_telemetry_socket(monkeypatch)

    client = DJIInterface("192.168.1.42", transport=Transport.BOTH)
    client._connect_telemetry_socket()

    assert sockets[-1].sent == [TCP_GAP_MODE_REQUEST]


def test_http_transport_does_not_request_gap_mode_telemetry(monkeypatch):
    sockets = _patch_fake_telemetry_socket(monkeypatch)

    client = DJIInterface("192.168.1.42", transport=Transport.HTTP)
    client._connect_telemetry_socket()

    assert sockets[-1].sent == []


def test_request_send_posts_to_normalized_endpoint(monkeypatch):
    posts = []

    class Response:
        content = b"accepted"

    def fake_post(url, data, timeout):
        posts.append((url, data, timeout))
        return Response()

    monkeypatch.setattr("lyrebird_groundstation.dji_client.requests.post", fake_post)
    client = DJIInterface("192.168.1.42")

    assert client.requestSend("send/takeoff", "") == "accepted"
    assert posts == [("http://192.168.1.42:8080/send/takeoff", "", 5)]


def test_command_helpers_format_requests():
    client = RecordingDJIInterface()

    assert client.requestSendStick(2, -2, 0.5, -0.5) == "ok"
    # The waypoint modes return the seq parsed out of the response rather than the body;
    # a response without "seq=" yields None, so the assertion is on the recorded call.
    assert client.requestSendGoToWaypointHoldHeading(1.0, 2.0, 3.0, 4.0, 5.5) is None
    assert client.requestAbortAll() == "ok"

    assert client.calls == [
        ("/send/stick", "0.3000,-0.3000,0.3000,-0.3000", False),
        ("/send/gotoWaypointHoldHeading", "1.0,2.0,3.0,4.0,5.5", False),
        ("/send/abortAll", "", False),
    ]


def test_request_set_setting_maps_key_to_endpoint():
    client = RecordingDJIInterface()

    assert client.requestSetSetting("maxFlightHeight", "120") == "ok"
    assert client.requestSetSetting("distanceLimitEnabled", "true") == "ok"
    assert client.requestSetSetting("streamingMode", "webrtc") == "ok"
    assert client.requestSetSetting("rcControlMode", "jp") == "ok"
    assert client.requestSetSetting("unknownKey", "x") == ""

    assert client.calls == [
        ("/send/setMaxFlightHeight", "120", False),
        ("/send/setDistanceLimitEnabled", "true", False),
        ("/send/streaming/mode", "webrtc", False),
        ("/send/setRcControlMode", "jp", False),
    ]


def test_request_rc_pairing_actions():
    client = RecordingDJIInterface()

    assert client.requestRcPairingStart() == "ok"
    assert client.requestRcPairingStop() == "ok"

    assert client.calls == [
        ("/send/rcPairing/start", "", False),
        ("/send/rcPairing/stop", "", False),
    ]


def test_get_settings_parses_json(monkeypatch):
    class Response:
        status_code = 200
        text = '{"droneName": "mini1", "videoSource": "drone"}'

    monkeypatch.setattr(
        "lyrebird_groundstation.dji_client.requests.get",
        lambda url, timeout: Response(),
    )
    client = DJIInterface("192.168.1.42")

    assert client.getSettings() == {"droneName": "mini1", "videoSource": "drone"}


def test_stop_telemetry_closes_socket_and_joins_thread():
    class Socket:
        def __init__(self):
            self.closed = False

        def close(self):
            self.closed = True

    class Thread:
        def __init__(self):
            self.join_timeout = None

        def join(self, timeout=None):
            self.join_timeout = timeout

    client = DJIInterface("192.168.1.42")
    client._running = True
    client._telemetry_socket = Socket()
    client._telemetry_thread = Thread()

    client.stopTelemetryStream()

    assert client._running is False
    assert client._telemetry_socket.closed is True
    assert client._telemetry_thread.join_timeout == 2


def test_every_command_path_goes_through_the_post_hook():
    """Regression guard for the Safety Computer token bypass.

    requestCapture, listMedia and downloadByName used to call requests.post directly, so
    DJIInterfaceSafety's header injection never reached them and ControlAuthority rejected
    them as Pilot traffic. They must all route through _post.
    """
    import inspect

    from lyrebird_groundstation import dji_client

    for name in ("requestSend", "requestCapture", "listMedia", "downloadByName"):
        source = inspect.getsource(getattr(dji_client.DJIInterface, name))
        assert "requests.post" not in source, f"{name} bypasses the _post hook"
        assert "self._post(" in source, f"{name} does not call _post"


def test_post_hook_is_the_only_direct_poster():
    import inspect

    from lyrebird_groundstation import dji_client

    posters = [
        name
        for name, member in inspect.getmembers(dji_client.DJIInterface, inspect.isfunction)
        if "requests.post" in inspect.getsource(member)
    ]
    assert posters == ["_post"], f"unexpected direct posters: {posters}"


def test_safety_client_tokens_capture_and_media_paths(monkeypatch):
    """The Safety Computer must authenticate capture and media commands, not just requestSend.

    These three used to post without the X-Safety-Token header, so ControlAuthority classified
    them as Pilot traffic and rejected them once Safety held authority.
    """
    from djiInterfaceSafety import SAFETY_TOKEN_HEADER, DJIInterfaceSafety
    from lyrebird_groundstation import dji_client

    seen = []

    class Response:
        status_code = 200
        headers: ClassVar[dict[str, str]] = {"Content-Type": "application/json"}
        text = "{}"
        content = b"{}"

        def json(self):
            return {}

    def fake_post(url, data=None, timeout=None, headers=None, **kwargs):
        seen.append((url, dict(headers or {})))
        return Response()

    monkeypatch.setattr(dji_client.requests, "post", fake_post)
    # X-Safety-Token is an HTTP-only mechanism (MAVLink authenticates via packet signing
    # instead), so this test pins the transport to HTTP regardless of the client default.
    monkeypatch.setenv("LB_TRANSPORT", "http")

    client = DJIInterfaceSafety(IP_RC="10.0.0.1", safety_token="98")
    client.requestSend("/send/takeoff", "")
    client.requestCapture()
    client.listMedia()
    client.downloadByName("DJI_0001.JPG", save_path="/dev/null")

    assert len(seen) == 4
    for url, headers in seen:
        assert headers.get(SAFETY_TOKEN_HEADER.rstrip(":")) == "98", f"no token on {url}"


def _bare_client():
    """A client with just enough state to exercise the telemetry cache, and no sockets."""
    client = dji_client.DJIInterface.__new__(dji_client.DJIInterface)
    client._telemetry = {}
    client._telemetry_seq = 0
    client._telemetry_lock = threading.Lock()
    client._timestamp_factory = lambda: "2026-08-26_00-00-00.000000"
    return client


def test_telemetry_update_reports_each_snapshot_once():
    """A consumer polling faster than the drone sends should publish once per snapshot.

    The ROS node polls at 20 Hz while the aircraft's TCP telemetry runs nearer 2 Hz, so without
    this the same sample was republished about ten times across 45-plus topics.
    """
    client = _bare_client()

    # Nothing received yet: a first-time caller gets nothing rather than an empty dict.
    sequence, telemetry = client.getTelemetryUpdate(0)
    assert telemetry is None

    client._process_telemetry_data("", b'{"batteryLevel": 91}\n')
    sequence, telemetry = client.getTelemetryUpdate(0)
    assert telemetry["batteryLevel"] == 91
    assert sequence == 1

    # Polling again without a new snapshot yields nothing, and does not move the sequence.
    for _ in range(5):
        again_seq, again = client.getTelemetryUpdate(sequence)
        assert again is None
        assert again_seq == sequence

    # A new snapshot is reported exactly once.
    client._process_telemetry_data("", b'{"batteryLevel": 90}\n')
    sequence, telemetry = client.getTelemetryUpdate(sequence)
    assert telemetry["batteryLevel"] == 90
    assert sequence == 2
    assert client.getTelemetryUpdate(sequence)[1] is None


def test_telemetry_update_returns_a_copy():
    """Mutating what a caller got back must not corrupt the cache the next caller reads."""
    client = _bare_client()

    client._process_telemetry_data("", b'{"batteryLevel": 50}\n')
    _, telemetry = client.getTelemetryUpdate(0)
    telemetry["batteryLevel"] = 0

    assert client.getTelemetry()["batteryLevel"] == 50
