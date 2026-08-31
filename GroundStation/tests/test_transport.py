"""Transport selection, and the MAVLink translation that stands in for the HTTP wire."""

import math
import struct
import time

import pytest
from lyrebird_groundstation.transport import (
    _COMMAND_MAP,
    _SPECIAL_SENDERS,
    AUTOSENSING_STATUS_CRC_EXTRA,
    AUTOSENSING_STATUS_ID,
    AUTOSENSING_STATUS_STRUCT,
    AUTOSENSING_TARGET_CRC_EXTRA,
    AUTOSENSING_TARGET_ID,
    AUTOSENSING_TARGET_STRUCT,
    CMD_DO_GRIPPER,
    CMD_DO_REPOSITION,
    CMD_DO_SET_MODE,
    CMD_NAV_TAKEOFF,
    CMD_USER_1,
    CMD_USER_2,
    COMP_ID_AUTOPILOT1,
    DEFAULT_MAVLINK_PORT,
    GRIPPER_ACTION_RELEASE,
    HTTP_ONLY_BY_DESIGN,
    MAV_RESULT_CANCELLED,
    MAV_RESULT_IN_PROGRESS,
    PARAM_EXT_TYPE_CUSTOM,
    PX4_MODE_OFFBOARD,
    PX4_MODE_POSCTL,
    UNSUPPORTED_PREFIX,
    USER1_GIMBAL_RELATIVE,
    USER1_RELEASE_MANUAL_OVERRIDE,
    USER1_RELEASE_SAFETY,
    USER2_CAPTURE_TEMPERATURE,
    USER2_CAPTURE_THERMAL_IMAGE,
    USER2_LRF_MEASURE,
    LB_FLAG_LRF_TARGET_VALID,
    LB_FLAG_MANUAL_OVERRIDE,
    LB_FLAG_READY_TO_TAKEOFF,
    LYREBIRD_CONFIG_CRC_EXTRA,
    LYREBIRD_CONFIG_ID,
    LYREBIRD_CONFIG_STRUCT,
    LYREBIRD_STATUS_ID,
    LYREBIRD_STATUS_SIZE,
    LYREBIRD_STATUS_STRUCT,
    MavlinkCommandChannel,
    MavlinkTelemetrySource,
    Transport,
    _crc_extra_for,
    _derive,
    _dji_heading,
    _trim,
    _x25crc,
    apply_mavlink_message,
    decode_lyrebird_status,
    flight_mode_name,
    sign_frame,
    signing_key_from_env,
    verify_frame_signature,
)

#: LYREBIRD_STATUS field order, so a fixture names what it sets instead of counting commas.
#: Every previous field addition broke every one of these tests; naming them makes a new field a
#: default rather than a rewrite.
_STATUS_FIELDS = [
    "time_boot_ms",
    "lrf_lat",
    "lrf_lon",
    "lrf_alt",
    "max_radius",
    "waypoint_seq",
    "yaw_seq",
    "altitude_seq",
    "time_to_home",
    "time_to_land",
    "total_time",
    "joint_pitch",
    "joint_roll",
    "zoom_fl",
    "optical_fl",
    "hybrid_fl",
    "battery_to_home",
    "battery_to_land",
    "flags",
    "reason",
]


def status_payload(**overrides):
    """A LYREBIRD_STATUS payload with everything zero except what the test names."""
    values = dict.fromkeys(_STATUS_FIELDS, 0)
    values["lrf_alt"] = 0.0
    values["max_radius"] = 0.0
    values["reason"] = b""
    values.update(overrides)
    return struct.pack(LYREBIRD_STATUS_STRUCT, *(values[name] for name in _STATUS_FIELDS))


class FakeMessage:
    """A decoded MAVLink message, with the header a real one carries."""

    def __init__(self, kind, src_component=COMP_ID_AUTOPILOT1, **fields):
        self._kind = kind
        self._src_component = src_component
        self.__dict__.update(fields)

    def get_type(self):
        return self._kind

    def get_header(self):
        return type("Header", (), {"srcComponent": self._src_component})()


# -- selection --------------------------------------------------------------------------------


def test_default_transport_is_both_so_mavlink_is_used_without_opting_in():
    assert Transport.from_env({}) is Transport.BOTH
    assert Transport.from_env({"LB_TRANSPORT": ""}) is Transport.BOTH


@pytest.mark.parametrize(
    ("value", "expected"),
    [("mavlink", Transport.MAVLINK), ("both", Transport.BOTH), ("HTTP", Transport.HTTP)],
)
def test_transport_is_read_from_the_environment(value, expected):
    assert Transport.from_env({"LB_TRANSPORT": value}) is expected


def test_a_misspelled_transport_fails_rather_than_silently_using_http():
    # A typo that quietly ran over HTTP would produce a passing "MAVLink" test result.
    with pytest.raises(ValueError, match="LB_TRANSPORT"):
        Transport.from_env({"LB_TRANSPORT": "mavlnk"})


def test_only_mavlink_only_mode_refuses_to_fall_back():
    assert Transport.MAVLINK.uses_mavlink and not Transport.MAVLINK.allows_http_fallback
    assert Transport.BOTH.uses_mavlink and Transport.BOTH.allows_http_fallback
    assert not Transport.HTTP.uses_mavlink and Transport.HTTP.allows_http_fallback


# -- telemetry translation --------------------------------------------------------------------


def test_heading_is_converted_to_djis_signed_convention():
    # MAVLink reports 0..360, DJI reports -180..180. The same bearing must read the same on
    # either wire, or a consumer sees a 360-degree jump when the transport changes.
    assert _dji_heading(0) == 0.0
    assert _dji_heading(9000) == 90.0
    assert _dji_heading(18000) == 180.0
    assert _dji_heading(19540) == pytest.approx(-164.6)
    assert _dji_heading(27000) == -90.0
    assert _dji_heading(65535) == 0.0, "65535 is MAVLink's unknown"


def test_only_the_autopilots_heartbeat_sets_the_flight_mode():
    telemetry = {}
    apply_mavlink_message(telemetry, FakeMessage("HEARTBEAT", custom_mode=3 << 16))
    assert telemetry["flightMode"] == "GPS_NORMAL"

    # A Lyrebird aircraft also heartbeats from its camera component, with a meaningless zero
    # custom_mode. Believing it reports UNKNOWN for a drone sitting in position hold.
    changed = apply_mavlink_message(
        telemetry, FakeMessage("HEARTBEAT", src_component=100, custom_mode=0)
    )
    assert not changed
    assert telemetry["flightMode"] == "GPS_NORMAL"


def test_unadvertised_modes_read_as_unknown():
    assert flight_mode_name(0) == "UNKNOWN"
    assert flight_mode_name(99 << 16) == "UNKNOWN"


def test_position_fills_the_same_keys_the_http_stream_uses():
    telemetry = {}
    apply_mavlink_message(
        telemetry,
        FakeMessage(
            "GLOBAL_POSITION_INT",
            lat=465180000,
            lon=65660000,
            relative_alt=30000,
            hdg=19540,
            vx=100,
            vy=-50,
            vz=25,
        ),
    )
    assert telemetry["location"] == {
        "latitude": pytest.approx(46.518),
        "longitude": pytest.approx(6.566),
        "altitude": pytest.approx(30.0),
    }
    assert telemetry["altitude"] == pytest.approx(30.0)
    assert telemetry["heading"] == pytest.approx(-164.6)
    assert telemetry["speed"] == {"x": 1.0, "y": -0.5, "z": 0.25}


def test_attitude_is_reported_in_degrees_like_the_http_stream():
    telemetry = {}
    apply_mavlink_message(
        telemetry, FakeMessage("ATTITUDE", roll=0.0, pitch=math.radians(-11.3), yaw=math.pi)
    )
    assert telemetry["attitude"]["pitch"] == pytest.approx(-11.3)
    assert telemetry["attitude"]["yaw"] == pytest.approx(180.0)


def test_home_position_carries_the_location_but_not_the_latch():
    """The aircraft knows where home is before its "home recorded" latch closes.

    Inferring the latch from this message arriving would report homeSet true while the HTTP
    surface reported it false, which is exactly the kind of disagreement between the two wires
    this work exists to prevent.
    """
    telemetry = {}
    apply_mavlink_message(
        telemetry,
        FakeMessage("HOME_POSITION", latitude=465180000, longitude=65660000, altitude=400000),
    )
    assert telemetry["homeLocation"]["latitude"] == pytest.approx(46.518)
    assert "homeSet" not in telemetry, "the latch comes from LYREBIRD_STATUS, not from here"


def test_mission_item_reached_feeds_the_same_latch_the_http_surface_exposes():
    telemetry = {}
    apply_mavlink_message(telemetry, FakeMessage("MISSION_ITEM_REACHED", seq=3))
    assert telemetry["waypointReached"] is True
    assert telemetry["waypointSeq"] == 3


def test_an_unmapped_message_changes_nothing():
    telemetry = {"heading": 12.0}
    assert not apply_mavlink_message(telemetry, FakeMessage("PING"))
    assert telemetry == {"heading": 12.0}


def test_the_ground_station_announces_itself_with_a_gcs_heartbeat():
    """Not decoration: the aircraft publishes video to a ground station it has heard from.

    A transport that only listens is never heard from, so telemetry arrived and video never
    started. The heartbeat is what makes a listening ground station visible.
    """
    from pymavlink.dialects.v20 import common as mavlink_common

    source = MavlinkTelemetrySource(peer_host="10.0.0.5")
    sent = []
    source._socket = type("S", (), {"sendto": lambda self, b, a: sent.append((b, a))})()
    source._send_heartbeat()

    assert sent, "a heartbeat must actually be transmitted"
    frame, address = sent[0]
    assert address == ("10.0.0.5", DEFAULT_MAVLINK_PORT)

    parser = mavlink_common.MAVLink(None)
    parser.robust_parsing = True
    msg = (parser.parse_buffer(frame) or [None])[0]
    assert msg.get_type() == "HEARTBEAT"
    assert msg.type == mavlink_common.MAV_TYPE_GCS, "we are a ground station, not a vehicle"


def test_the_heartbeat_goes_to_the_aircrafts_port_not_our_own():
    """Listening on 14551 beside QGroundControl must not send commands to 14551.

    The aircraft still listens on 14550; conflating the two ports sends every announcement to a
    port nothing is bound to, and fails silently.
    """
    source = MavlinkTelemetrySource(port=14551, peer_host="10.0.0.5", peer_port=14550)
    sent = []
    source._socket = type("S", (), {"sendto": lambda self, b, a: sent.append(a)})()
    source._send_heartbeat()
    assert sent == [("10.0.0.5", 14550)]


def test_the_heartbeat_frame_is_built_once_and_reused():
    source = MavlinkTelemetrySource(peer_host="10.0.0.5")
    sent = []
    source._socket = type("S", (), {"sendto": lambda self, b, a: sent.append(b)})()
    source._send_heartbeat()
    source._send_heartbeat()
    assert sent[0] == sent[1]


# -- command translation ----------------------------------------------------------------------


def _decode(frame):
    from pymavlink.dialects.v20 import common as mavlink_common

    parser = mavlink_common.MAVLink(None)
    parser.robust_parsing = True
    return (parser.parse_buffer(frame) or [None])[0]


def test_a_goto_is_sent_as_command_int_so_the_position_survives():
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP["/send/gotoWaypointHoldHeading"]("46.5180001,6.5660007,30,90,5")
    decoded = _decode(channel._frame_command(command, params))

    assert decoded.get_type() == "COMMAND_INT", "COMMAND_LONG would round lat/lon through float32"
    assert decoded.command == CMD_DO_REPOSITION
    assert decoded.x == 465180001
    assert decoded.y == 65660007
    assert decoded.frame == 6, "altitudes are above home, as everywhere else in Lyrebird"
    assert decoded.param4 == pytest.approx(90.0)


def test_nose_forward_is_expressed_as_a_nan_heading():
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP["/send/gotoWaypointNoseForward"]("46.518,6.566,30,0,5")
    decoded = _decode(channel._frame_command(command, params))
    assert math.isnan(decoded.param4), "a zero here would silently mean 'hold north'"


def test_a_takeoff_carries_its_altitude_in_param7():
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP["/send/takeoff"]("15")
    decoded = _decode(channel._frame_command(command, params))
    assert decoded.get_type() == "COMMAND_LONG"
    assert decoded.command == CMD_NAV_TAKEOFF
    assert decoded.param7 == pytest.approx(15.0)


def test_every_setting_endpoint_has_a_mavlink_form():
    """Numbers go through PARAM_SET, strings through PARAM_EXT_SET, and none are left behind."""
    import lyrebird_groundstation.dji_client as client

    covered = set(_COMMAND_MAP) | set(_SPECIAL_SENDERS)
    missing = sorted(set(client.SETTING_ENDPOINTS.values()) - covered)
    assert not missing, f"settings with no MAVLink form: {missing}"


def test_a_string_setting_goes_out_as_param_ext_set():
    """A server address has no honest float encoding, which is what the extended protocol is for."""
    channel = MavlinkCommandChannel("127.0.0.1")
    sent = []
    channel._socket = _FakeSocket([])
    channel._socket.sendto = lambda data, address: sent.append(data)
    channel.set_text_parameter("LB_MEDIAMTX", "192.168.50.127:8889", timeout=0.05)

    decoded = _decode(sent[0])
    assert decoded.get_type() == "PARAM_EXT_SET"
    assert _trim(decoded.param_id) == "LB_MEDIAMTX"
    assert _trim(decoded.param_value) == "192.168.50.127:8889"
    assert decoded.param_type == PARAM_EXT_TYPE_CUSTOM


def test_a_refused_string_write_is_detected_from_the_echoed_value():
    """PARAM_EXT_ACK echoes what the setting now holds, so a refusal needs no second read."""
    from pymavlink.dialects.v20 import common as mavlink_common

    class Sink:
        buf = b""

        def write(self, data):
            self.buf += data

    sink = Sink()
    mavlink_common.MAVLink(sink, srcSystem=1, srcComponent=1).param_ext_ack_send(
        b"LB_VIDEO_SRC",
        b"drone",
        PARAM_EXT_TYPE_CUSTOM,
        1,  # PARAM_ACK_VALUE_UNSUPPORTED
    )

    channel = MavlinkCommandChannel("127.0.0.1")
    channel._socket = _FakeSocket([sink.buf])
    reply = channel.set_text_parameter("LB_VIDEO_SRC", "nonsense", timeout=1.0)

    assert reply.startswith("REJECTED")
    assert "drone" in reply, "the reply must say what the setting actually holds"


def test_an_endpoint_with_no_mavlink_equivalent_is_refused_not_guessed():
    channel = MavlinkCommandChannel("127.0.0.1")
    # Pairing is a maintenance action with no MAVLink equivalent and no plans for one.
    assert not channel.supports("/send/rcPairing/start")
    assert channel.send("/send/rcPairing/start", "").startswith(UNSUPPORTED_PREFIX)


def test_every_command_the_ros_controller_uses_has_a_mavlink_form():
    """The ROS ground station drives Lyrebird through these; a gap means it cannot run on MAVLink.

    Pinned as a test because the failure is silent in the other direction: an endpoint added to
    the ROS controller without a mapping here would simply be refused at flight time.
    """
    required = {
        "/send/takeoff",
        "/send/land",
        "/send/RTH",
        "/send/gotoWaypointNoseForward",
        "/send/gotoWaypointHoldHeading",
        "/send/gotoYaw",
        "/send/gotoAltitude",
        "/send/camera/zoom",
        "/send/camera/startRecording",
        "/send/camera/stopRecording",
        "/send/capture",
        "/send/gimbal/pitch",
        "/send/gimbal/yaw",
        "/send/gimbal/rel_pitch",
        "/send/gimbal/rel_yaw",
        "/send/stick",
        "/send/navigateTrajectoryDJINative",
        "/send/abortMission",
        "/send/abortAll",
        "/send/abort/DJIMission",
        "/send/enableVirtualStick",
        "/send/deactivateManualOverride",
        "/send/setRTHAltitude",
        "/send/setSetting",
        "/send/lrf/measure",
        "/send/captureTemperature",
        "/send/drop",
    }
    covered = set(_COMMAND_MAP) | set(_SPECIAL_SENDERS)
    assert not (required - covered), f"unmapped: {sorted(required - covered)}"


@pytest.mark.parametrize(
    "endpoint", ["/send/abortMission", "/send/abortAll", "/send/abort/DJIMission"]
)
def test_every_abort_is_a_request_for_position_hold(endpoint):
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP[endpoint](" ")
    decoded = _decode(channel._frame_command(command, params))
    assert decoded.command == CMD_DO_SET_MODE
    assert decoded.param2 == PX4_MODE_POSCTL, "an abort must land in position hold"


def test_entering_stick_control_asks_for_offboard():
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP["/send/enableVirtualStick"](" ")
    decoded = _decode(channel._frame_command(command, params))
    assert decoded.command == CMD_DO_SET_MODE
    assert decoded.param2 == PX4_MODE_OFFBOARD


def test_the_user_commands_are_told_apart_by_their_selector():
    channel = MavlinkCommandChannel("127.0.0.1")
    cases = {
        "/send/lrf/measure": (CMD_USER_2, USER2_LRF_MEASURE),
        "/send/captureTemperature": (CMD_USER_2, USER2_CAPTURE_TEMPERATURE),
        "/send/captureThermalImage": (CMD_USER_2, USER2_CAPTURE_THERMAL_IMAGE),
        "/send/deactivateManualOverride": (CMD_USER_1, USER1_RELEASE_MANUAL_OVERRIDE),
        "/releaseSafetyControl": (CMD_USER_1, USER1_RELEASE_SAFETY),
        "/send/gimbal/rel_pitch": (CMD_USER_1, USER1_GIMBAL_RELATIVE),
    }
    for endpoint, (expected_command, selector) in cases.items():
        command, params = _COMMAND_MAP[endpoint]("0,-15,0")
        decoded = _decode(channel._frame_command(command, params))
        assert decoded.command == expected_command, endpoint
        assert decoded.param1 == pytest.approx(selector), endpoint


def test_safety_release_is_now_a_mavlink_command_not_http_only():
    """Tier 3: the signed release is the counterpart of the signed seizure."""
    channel = MavlinkCommandChannel("127.0.0.1")
    assert channel.supports("/releaseSafetyControl")
    assert "/releaseSafetyControl" not in HTTP_ONLY_BY_DESIGN


def test_an_accepted_thermal_capture_reports_captured_without_a_descriptor():
    """The HTTP route returns a JSON descriptor of the stored files; MAVLink has no room in the
    ack, so an accepted shutter is reported plainly -- the files are fetched by name later."""
    channel = MavlinkCommandChannel("127.0.0.1")
    channel._socket = _FakeSocket([_ack_frame(CMD_USER_2, 0)])  # MAV_RESULT_ACCEPTED
    reply = channel.send("/send/captureThermalImage", "", timeout=1.0)
    assert reply == '{"captured": true}'


def test_a_superseded_first_ack_is_bookkeeping_not_a_refusal():
    """A ground station that re-issues a goto every second must not see the old leg as a failure."""
    channel = MavlinkCommandChannel("127.0.0.1")
    channel._socket = _FakeSocket([_ack_frame(CMD_DO_REPOSITION, MAV_RESULT_CANCELLED)])
    reply = channel.send("/send/gotoWaypointHoldHeading", "46.518,6.566,30,90,5", timeout=1.0)
    assert reply.startswith("SUPERSEDED")


def test_http_only_endpoints_are_intentionally_absent_and_reasoned():
    """The registry pins that these are HTTP-only by design, not by accident."""
    channel = MavlinkCommandChannel("127.0.0.1")
    assert HTTP_ONLY_BY_DESIGN, "the registry must not be empty"
    for endpoint, reason in HTTP_ONLY_BY_DESIGN.items():
        assert not channel.supports(endpoint), (
            f"{endpoint} gained a MAVLink form without a registry update"
        )
        assert reason, f"{endpoint} needs a reason for being HTTP-only"
    # And they must not be silently re-added to the command map either.
    covered = set(_COMMAND_MAP) | set(_SPECIAL_SENDERS)
    assert not (set(HTTP_ONLY_BY_DESIGN) & covered), (
        "an HTTP-only endpoint leaked into the MAVLink map"
    )


def test_a_relative_gimbal_nudge_moves_only_the_axis_it_names():
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP["/send/gimbal/rel_pitch"]("0,-15,0")
    pitch_cmd = _decode(channel._frame_command(command, params))
    assert pitch_cmd.param2 == pytest.approx(-15.0)
    assert pitch_cmd.param3 == 0.0, "a pitch nudge must not also swing the yaw"

    command, params = _COMMAND_MAP["/send/gimbal/rel_yaw"]("0,0,30")
    yaw_cmd = _decode(channel._frame_command(command, params))
    assert yaw_cmd.param2 == 0.0
    assert yaw_cmd.param3 == pytest.approx(30.0)


def test_a_drop_is_a_gripper_release():
    channel = MavlinkCommandChannel("127.0.0.1")
    command, params = _COMMAND_MAP["/send/drop"]("")
    decoded = _decode(channel._frame_command(command, params))
    assert decoded.command == CMD_DO_GRIPPER
    assert decoded.param2 == GRIPPER_ACTION_RELEASE


def test_sticks_are_sent_as_manual_control_on_the_right_axes():
    """DJI's sticks are left=yaw/throttle, right=roll/pitch; MAVLink's axes are named by meaning.

    Getting this mapping wrong swaps yaw for roll on a flying aircraft, which is why it is pinned.
    """
    sent = {}
    channel = MavlinkCommandChannel("127.0.0.1")
    channel._socket = type("S", (), {"sendto": lambda self, b, a: sent.update(frame=b)})()
    channel.send("/send/stick", "0.1,0.2,0.3,0.4")

    decoded = _decode(sent["frame"])
    assert decoded.get_type() == "MANUAL_CONTROL"
    assert decoded.r == 100, "leftX is yaw"
    assert decoded.z == 200, "leftY is throttle"
    assert decoded.y == 300, "rightX is roll"
    assert decoded.x == 400, "rightY is pitch"


def test_manual_control_axes_are_clamped():
    sent = {}
    channel = MavlinkCommandChannel("127.0.0.1")
    channel._socket = type("S", (), {"sendto": lambda self, b, a: sent.update(frame=b)})()
    channel.send_manual_control(roll=5.0, pitch=-5.0, throttle=0.0, yaw=0.0)

    decoded = _decode(sent["frame"])
    assert decoded.y == 1000 and decoded.x == -1000


# -- the Lyrebird dialect ---------------------------------------------------------------------


def test_the_hand_decoder_matches_the_dialect_definition():
    """Regenerate LYREBIRD_STATUS from lyrebird.xml and check our layout still matches.

    transport.py unpacks this message by hand rather than shipping a generated dialect, which is
    cheap for one message but silently wrong the moment the XML changes. This test regenerates the
    definition and compares, so the XML stays the source of truth in practice and not just in
    principle.
    """
    import pathlib
    import shutil
    import subprocess
    import sys
    import tempfile

    xml = pathlib.Path(__file__).resolve().parents[1] / "mavlink" / "lyrebird.xml"
    assert xml.exists(), xml

    pymavlink = pytest.importorskip("pymavlink")
    definitions = pathlib.Path(pymavlink.__file__).parent / "message_definitions" / "v1.0"

    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        for source in definitions.glob("*.xml"):
            shutil.copy(source, work / source.name)
        shutil.copy(xml, work / "lyrebird.xml")

        generate = (
            "from pymavlink.generator import mavgen; import argparse; "
            "mavgen.mavgen(argparse.Namespace(language='Python', wire_protocol='2.0', "
            "output='dialect', error_limit=200, validate=False, strict_units=False), "
            "['lyrebird.xml'])"
        )
        subprocess.run([sys.executable, "-c", generate], cwd=work, check=True, capture_output=True)

        sys.path.insert(0, str(work))
        try:
            import dialect  # type: ignore[import-not-found]

            message = dialect.MAVLink_lyrebird_status_message
            assert message.id == LYREBIRD_STATUS_ID
            assert message.unpacker.format == LYREBIRD_STATUS_STRUCT
            assert message.unpacker.size == LYREBIRD_STATUS_SIZE
        finally:
            sys.path.remove(str(work))
            sys.modules.pop("dialect", None)


def test_a_status_frame_from_a_mismatched_build_is_refused_not_misread():
    """The failure this check exists for, reproduced.

    An aircraft on an older build sends an older layout of LYREBIRD_STATUS. Padding that out
    and decoding anyway produced confident nonsense on a live drone -- the takeoff reason read
    twelve bytes late as "ISION", focal lengths that were really ASCII pairs. CRC_EXTRA is
    derived from the field definitions, so it changes when they do, which is what tells the two
    apart.
    """

    source = MavlinkTelemetrySource(peer_host="")
    old_payload = struct.pack(
        "<IiiffHHHHHHBBB24s", 0, 0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, b"NON_GPS_NONVISION"
    )

    # Framed exactly as the old build framed it, including its own CRC_EXTRA of 127.
    header = bytes([0xFD, len(old_payload), 0, 0, 0, 1, 1]) + (42100).to_bytes(3, "little")
    from pymavlink.generator.mavcrc import x25crc

    crc = x25crc(header[1:] + old_payload)
    crc.accumulate(bytes([127]))  # the old build's CRC_EXTRA, before the seq fields existed
    frame = header + old_payload + crc.crc.to_bytes(2, "little")

    assert not source._apply_lyrebird_status(frame)
    assert "takeoffBlockReason" not in source._telemetry


def test_a_current_status_frame_passes_the_checksum():
    """The other half: the gate must not reject frames it is supposed to accept.

    Worth its own test because the first version of this check did exactly that -- it fed
    CRC_EXTRA through a str, and a value above 127 encodes as two UTF-8 bytes, so every genuine
    frame failed and the panel quietly showed a third of the telemetry it should have.
    """

    payload = status_payload(
        time_boot_ms=1234,
        lrf_lat=465180000,
        lrf_lon=65660000,
        lrf_alt=12.5,
        max_radius=900.0,
        time_to_home=45,
        time_to_land=18,
        total_time=63,
        zoom_fl=24,
        optical_fl=25,
        hybrid_fl=26,
        battery_to_home=18,
        battery_to_land=12,
        flags=LB_FLAG_MANUAL_OVERRIDE | LB_FLAG_READY_TO_TAKEOFF | LB_FLAG_LRF_TARGET_VALID,
        reason=b"NONE",
    )
    status = decode_lyrebird_status(payload)

    assert status["isManualOverrideActive"] is True
    assert status["readyToTakeoff"] is True
    assert status["homeSet"] is False
    assert status["takeoffBlockReason"] == "NONE"
    assert status["timeNeededToGoHome"] == 45
    assert status["batteryNeededToLand"] == 12
    assert (status["zoomFl"], status["opticalFl"], status["hybridFl"]) == (24, 25, 26)
    assert status["lrfTarget"] == [pytest.approx(46.518), pytest.approx(6.566), pytest.approx(12.5)]


def test_an_unlocked_rangefinder_reports_no_target_rather_than_null_island():
    payload = status_payload()
    # 0,0 is a real place off West Africa; reporting it as a target would be worse than reporting
    # nothing, because a consumer cannot tell it apart from a genuine fix.
    assert decode_lyrebird_status(payload)["lrfTarget"] is None


def test_a_truncated_payload_still_decodes():
    """MAVLink 2 drops trailing zeros, so a mostly-empty status arrives short."""
    full = status_payload(time_boot_ms=7)
    assert decode_lyrebird_status(full.rstrip(b"\x00"))["takeoffBlockReason"] == "UNKNOWN"


# -- derived and gimbal telemetry ---------------------------------------------------------------


def test_distance_to_home_is_computed_rather_than_transmitted():
    telemetry = {
        "location": {"latitude": 46.5180, "longitude": 6.5660},
        "homeLocation": {"latitude": 46.5190, "longitude": 6.5670},
        # Deliberately without homeSet: a distance is useful as soon as home has coordinates.
        "homeSet": False,
    }
    _derive(telemetry)
    # ~135 m north-east; checked against the great-circle distance rather than a magic number.
    assert telemetry["distanceToHome"] == pytest.approx(135.0, abs=5.0)


def test_distance_to_home_is_zero_until_home_has_coordinates():
    telemetry = {"location": {"latitude": 46.5, "longitude": 6.5}, "homeSet": False}
    _derive(telemetry)
    assert telemetry["distanceToHome"] == 0.0

    # (0, 0) is the SDK's unset value and a real place off West Africa; reporting the 2,559 km to
    # it, as the HTTP surface does, is worse than reporting nothing.
    telemetry["homeLocation"] = {"latitude": 0.0, "longitude": 0.0}
    _derive(telemetry)
    assert telemetry["distanceToHome"] == 0.0


def test_gimbal_attitude_reports_the_world_frame_angles():
    telemetry = {}
    q = _euler_to_quat(0.0, math.radians(-45.0), math.radians(90.0))
    apply_mavlink_message(telemetry, FakeMessage("GIMBAL_DEVICE_ATTITUDE_STATUS", q=q))
    assert telemetry["gimbalAttitude"]["pitch"] == pytest.approx(-45.0, abs=0.01)
    assert telemetry["gimbalAttitude"]["yaw"] == pytest.approx(90.0, abs=0.01)


def test_delta_yaw_is_interpreted_as_radians():
    """The wire's delta_yaw is radians; a receiver converts it to the degrees the HTTP stream uses.

    This is the contract the aircraft is built against: a joint yaw of 30 degrees travels as
    ~0.524 rad and must come back as 30 degrees. The failure this guards against is invisible
    while the aircraft is still -- joint yaw sits near zero, and a zero is a zero in either unit
    -- and shows up the moment the aircraft moves and the gimbal compensates. It is pinned here
    rather than left to a live-aircraft comparison for exactly that reason.
    """
    telemetry = {}
    q = _euler_to_quat(0.0, 0.0, 0.0)
    apply_mavlink_message(
        telemetry,
        FakeMessage("GIMBAL_DEVICE_ATTITUDE_STATUS", q=q, delta_yaw=math.radians(30.0)),
    )
    assert telemetry["_gimbalJointYaw"] == pytest.approx(30.0)


def test_a_real_gimbal_frame_recovers_the_joint_yaw_in_degrees():
    """The whole wire contract in one test: a frame built the way the aircraft builds it.

    A 0.2-degree joint yaw travels as delta_yaw = 0.00349 rad (the MAVLink field is radians) and
    must come back as 0.2 degrees. This is the exact failure the MAVLink tab caught on a live
    aircraft: shipping degrees instead put 0.2 on the wire and the receiver read 11.46 -- a frame
    that decodes cleanly and means the wrong thing, visible only once the aircraft moves.
    """
    from pymavlink.dialects.v20 import common as mavlink_common

    class Sink:
        buf = b""

        def write(self, data):
            self.buf += data

    sink = Sink()
    mavlink_common.MAVLink(sink, srcSystem=1, srcComponent=154).gimbal_device_attitude_status_send(
        0,  # target_system: broadcast
        0,  # target_component
        0,  # time_boot_ms
        32,  # flags: GIMBAL_DEVICE_FLAGS_YAW_IN_VEHICLE_FRAME
        [1.0, 0.0, 0.0, 0.0],  # q: gimbal level in the vehicle frame
        float("nan"),
        float("nan"),
        float("nan"),  # angular rates: not reported
        0,  # failure_flags
        math.radians(0.2),  # delta_yaw: radians per spec, as the aircraft now sends
        float("nan"),  # delta_yaw_velocity
        0,  # gimbal_device_id
    )
    parser = mavlink_common.MAVLink(None)
    (msg,) = parser.parse_buffer(sink.buf)
    telemetry = {}
    apply_mavlink_message(telemetry, msg)
    assert telemetry["_gimbalJointYaw"] == pytest.approx(0.2, abs=1e-3)
    assert telemetry["gimbalAttitude"]["yaw"] == pytest.approx(0.0, abs=1e-3)


def test_the_reported_joint_angles_beat_the_derived_ones():
    """When the aircraft reports its own joint angles, they win.

    Deriving them from the two world attitudes recovers the right sign and slope but lands about
    1.5 degrees out, because DJI's joint angles include a mounting offset the world attitude does
    not describe. Measured over 48 samples of a hand-tilted aircraft, so the aircraft reports them
    and this checks the derivation does not overwrite the measurement.
    """
    telemetry = {
        "attitude": {"roll": 0.0, "pitch": -12.5, "yaw": 0.0},
        "_gimbalQuaternion": _euler_to_quat(0.0, 0.0, 0.0),
        "gimbalAttitude": {"roll": 0.0, "pitch": 0.0, "yaw": 0.0},
        "_gimbalJointPitch": 14.1,
        "_gimbalJointRoll": -0.4,
        "_gimbalJointYaw": 2.5,
    }
    _derive(telemetry)
    joint = telemetry["gimbalJointAttitude"]
    # The composition would have said 12.5; the aircraft says 14.1, and it knows.
    assert joint["pitch"] == pytest.approx(14.1)
    assert joint["roll"] == pytest.approx(-0.4)
    assert joint["yaw"] == pytest.approx(2.5), "yaw comes from the standard message's delta_yaw"

    # And they must survive the next message: _derive runs on every change, so consuming them
    # would let the following ATTITUDE replace the measurement with a derivation of it.
    telemetry["attitude"] = {"roll": 0.0, "pitch": -12.6, "yaw": 0.0}
    _derive(telemetry)
    assert telemetry["gimbalJointAttitude"]["pitch"] == pytest.approx(14.1)


def test_the_joint_attitude_is_the_gimbal_relative_to_a_tilted_aircraft():
    """A gimbal holding level on a rolled aircraft is not level in the body frame.

    Subtracting euler angles happens to work here, but the code composes quaternions because the
    subtraction stops being correct exactly when the aircraft stops being close to level.
    """
    telemetry = {
        "attitude": {"roll": 1.3, "pitch": 0.1, "yaw": 0.0},
        "_gimbalQuaternion": _euler_to_quat(0.0, 0.0, 0.0),
        "gimbalAttitude": {"roll": 0.0, "pitch": 0.0, "yaw": 0.0},
    }
    _derive(telemetry)
    joint = telemetry["gimbalJointAttitude"]
    # The magnitude is what the composition establishes; the sign against DJI's own convention is
    # not yet established, so this pins the size and leaves GIMBAL_JOINT_SIGN to settle the rest.
    assert abs(joint["roll"]) == pytest.approx(1.3, abs=0.01)
    assert abs(joint["pitch"]) == pytest.approx(0.1, abs=0.01)


def test_the_joint_attitude_falls_back_before_any_attitude_arrives():
    telemetry = {
        "_gimbalQuaternion": _euler_to_quat(0.0, math.radians(-30.0), 0.0),
        "gimbalAttitude": {"roll": 0.0, "pitch": -30.0, "yaw": 0.0},
    }
    _derive(telemetry)
    assert telemetry["gimbalJointAttitude"]["pitch"] == pytest.approx(-30.0)


def test_unreported_focal_lengths_read_as_minus_one_not_zero():
    status = decode_lyrebird_status(status_payload())
    # 0 mm is not a lens. The HTTP stream says -1 for "not reported" and so must this.
    assert (status["zoomFl"], status["opticalFl"], status["hybridFl"]) == (-1, -1, -1)


def test_the_rangefinder_range_arrives_as_a_standard_distance_sensor():
    telemetry = {}
    apply_mavlink_message(telemetry, FakeMessage("DISTANCE_SENSOR", current_distance=4250))
    assert telemetry["lrfDistance"] == pytest.approx(42.5)


def _euler_to_quat(roll: float, pitch: float, yaw: float):
    cr, sr = math.cos(roll / 2), math.sin(roll / 2)
    cp, sp = math.cos(pitch / 2), math.sin(pitch / 2)
    cy, sy = math.cos(yaw / 2), math.sin(yaw / 2)
    return [
        cr * cp * cy + sr * sp * sy,
        sr * cp * cy - cr * sp * sy,
        cr * sp * cy + sr * cp * sy,
        cr * cp * sy - sr * sp * cy,
    ]


# -- completion reporting -----------------------------------------------------------------------


def test_a_goto_returns_at_once_and_raises_its_latch_when_it_arrives():
    """Accepted is not arrived, but the caller must not be made to wait for arrival either.

    The HTTP contract is that a goto returns a seq immediately and the caller polls the reach
    latch. Blocking here until the aircraft arrived would stall a ROS callback for the whole
    flight, so the completion is picked up in the background and raises the latch there.
    """
    channel = MavlinkCommandChannel("127.0.0.1")
    latched = {}
    channel._on_latch = latched.update
    channel._socket = _FakeSocket(
        [
            _ack_frame(CMD_DO_REPOSITION, MAV_RESULT_IN_PROGRESS),
            _ack_frame(CMD_DO_REPOSITION, 0),
        ]
    )

    started = time.time()
    reply = channel.send("/send/gotoWaypointHoldHeading", "46.518,6.566,30,90,5", timeout=2.0)
    elapsed = time.time() - started

    assert reply.startswith("ACCEPTED")
    assert elapsed < 1.0, "the caller must not wait for the flight"

    seq = int(reply.split("seq=")[1].split()[0])
    deadline = time.time() + 3.0
    while time.time() < deadline and "waypointReached" not in latched:
        time.sleep(0.02)

    assert latched.get("waypointReached") is True
    assert latched.get("waypointSeq") == seq, "the arrival must be reported under its own seq"


def test_a_command_that_never_completes_leaves_its_latch_down():
    """An accepted command that never arrives must not look like an arrival."""
    channel = MavlinkCommandChannel("127.0.0.1")
    latched = {}
    channel._on_latch = latched.update
    channel._socket = _FakeSocket([_ack_frame(CMD_DO_REPOSITION, MAV_RESULT_IN_PROGRESS)])

    reply = channel.send("/send/gotoWaypointHoldHeading", "46.518,6.566,30,90,5", timeout=1.0)

    assert reply.startswith("ACCEPTED"), "it was accepted; it just has not finished"
    time.sleep(0.3)
    assert "waypointReached" not in latched


def test_a_refused_command_is_reported_as_refused():
    channel = MavlinkCommandChannel("127.0.0.1")
    channel._socket = _FakeSocket([_ack_frame(CMD_DO_REPOSITION, 3)])  # MAV_RESULT_DENIED
    reply = channel.send("/send/gotoWaypointHoldHeading", "46.518,6.566,30,90,5", timeout=1.0)
    assert reply.startswith("REJECTED")


# The aircraft's own reference frames, from MavlinkSigningTest.kt. They were signed by
# pymavlink's signer with the key 00 01 ... 1f, so reproducing them byte-for-byte is the
# cross-implementation proof that this signer agrees with the aircraft's MavlinkSigning.
SIGNING_KEY = bytes(range(32))
VALID_FIRST = (
    "fd20010000ffbe4c00000000803f00000000000000000000000000000000000000000000000090010101"
    "49d00040420f00000083b453f4492b"
)
VALID_SECOND = (
    "fd20010001ffbe4c00000000803f00000000000000000000000000000000000000000000000090010101"
    "a79a0041420f000000638d21c1617a"
)
TAMPERED = (
    "fd20010001ffbe4c00000000803f00000000000000000000000000000000000000000000000090010101"
    "a79a0041420f000000638d21c16185"
)
UNSIGNED = (
    "fd20000000ffbe4c00000000803f000000000000000000000000000000000000000000000000900101019e4e"
)


def test_verifier_accepts_the_aircrafts_reference_frames():
    """The app's own vectors verify here, so the hash matches MavlinkSigning on the aircraft."""
    assert verify_frame_signature(bytes.fromhex(VALID_FIRST), SIGNING_KEY)
    assert verify_frame_signature(bytes.fromhex(VALID_SECOND), SIGNING_KEY)
    # TAMPERED differs only in the last signature byte.
    assert not verify_frame_signature(bytes.fromhex(TAMPERED), SIGNING_KEY)
    # UNSIGNED carries no signed incompat flag.
    assert not verify_frame_signature(bytes.fromhex(UNSIGNED), SIGNING_KEY)


def test_signer_reproduces_the_aircrafts_reference_signature():
    """Re-signing the reference frame's unsigned content yields the reference bytes exactly."""
    signed = bytes.fromhex(VALID_FIRST)
    payload_length = signed[1]
    signature_start = 10 + payload_length + 2
    link_id = signed[signature_start]
    timestamp = int.from_bytes(signed[signature_start + 1 : signature_start + 7], "little")

    # Reconstruct what pymavlink produced before signing: strip the 13 signature bytes, clear
    # the signed incompat bit, and recompute the checksum without it.
    unsigned = bytearray(signed[:signature_start])
    unsigned[2] &= ~0x01
    message_id = int.from_bytes(unsigned[7:10], "little")
    crc = _x25crc(bytes(unsigned[1 : 1 + 9 + payload_length]) + bytes([_crc_extra_for(message_id)]))
    unsigned[10 + payload_length : 12 + payload_length] = crc.to_bytes(2, "little")

    resigned = sign_frame(bytes(unsigned), SIGNING_KEY, timestamp, link_id)
    assert resigned == signed


def test_a_signing_key_channel_emits_frames_the_aircraft_trusts():
    channel = MavlinkCommandChannel("127.0.0.1", signing_key=SIGNING_KEY)
    command, params = _COMMAND_MAP["/send/land"]("")
    frame = channel._frame_command(command, params)
    assert frame[2] & 0x01 != 0, "the signed incompat flag must be set"
    assert verify_frame_signature(frame, SIGNING_KEY)

    plain = MavlinkCommandChannel("127.0.0.1")
    unsigned = plain._frame_command(command, params)
    assert unsigned[2] & 0x01 == 0
    assert len(unsigned) == len(frame) - 13, "signing appends exactly the 13 signature bytes"


def test_signing_key_from_env_parses_64_hex():
    key = bytes(range(32))
    assert signing_key_from_env({"LB_MAVLINK_SIGNING_KEY": key.hex()}) == key
    assert signing_key_from_env({"LB_MAVLINK_SIGNING_KEY": "not-hex"}) is None
    assert signing_key_from_env({"LB_MAVLINK_SIGNING_KEY": "00"}) is None
    assert signing_key_from_env({}) is None


def _ack_frame(command: int, result: int) -> bytes:
    from pymavlink.dialects.v20 import common as mavlink_common

    class Sink:
        buf = b""

        def write(self, data):
            self.buf += data

    sink = Sink()
    mavlink_common.MAVLink(sink, srcSystem=1, srcComponent=1).command_ack_send(command, result)
    return sink.buf


class _FakeSocket:
    """Hands back queued frames, then behaves like a socket with nothing to read."""

    def __init__(self, frames):
        self._frames = frames

    def sendto(self, data, address):
        return len(data)

    def settimeout(self, timeout):
        return None

    def recvfrom(self, size):
        if not self._frames:
            # A real socket blocks until its timeout; raising instantly would spin the reader.
            time.sleep(0.02)
            raise TimeoutError
        return self._frames.pop(0), ("127.0.0.1", 14550)


# -- config and detections ----------------------------------------------------------------------


def _frame(message_id, payload, crc_extra):
    from pymavlink.generator.mavcrc import x25crc

    header = bytes([0xFD, len(payload), 0, 0, 0, 1, 1]) + message_id.to_bytes(3, "little")
    crc = x25crc(header[1:] + payload)
    crc.accumulate(bytes([crc_extra]))
    return header + payload + crc.crc.to_bytes(2, "little")


def test_config_arrives_as_the_keys_the_http_endpoint_returns():
    """GET /config is a read, and MAVLink streams state rather than answering reads."""
    payload = struct.pack(
        LYREBIRD_CONFIG_STRUCT, 8080, 8081, 1, b"mini1", b"192.168.50.172", b"whip"
    )
    source = MavlinkTelemetrySource(peer_host="")
    assert source._apply_lyrebird_status(
        _frame(LYREBIRD_CONFIG_ID, payload, LYREBIRD_CONFIG_CRC_EXTRA)
    )
    assert source._telemetry["droneName"] == "mini1"
    assert source._telemetry["ipAddress"] == "192.168.50.172"
    assert source._telemetry["httpPort"] == 8080
    assert source._telemetry["telemetryPort"] == 8081
    assert source._telemetry["videoMode"] == "whip"
    assert source._telemetry["hasThermal"] is True


def _target(frame_id, index, count, kind, rect, confidence=float("nan")):
    return struct.pack(
        AUTOSENSING_TARGET_STRUCT, 0, frame_id, *rect, confidence, index, count, kind
    )


def _status(frame_id, count, active=1, source=b"edge", threshold=0.25):
    return struct.pack(AUTOSENSING_STATUS_STRUCT, 0, frame_id, threshold, active, count, source)


def test_a_detection_cycle_is_published_only_once_it_is_whole():
    """Targets arrive one message each; publishing them as they land shows a half-built set."""
    source = MavlinkTelemetrySource(peer_host="")

    assert not source._apply_lyrebird_status(
        _frame(
            AUTOSENSING_TARGET_ID,
            _target(7, 0, 2, b"PERSON", (0.1, 0.2, 0.3, 0.4), 0.9),
            AUTOSENSING_TARGET_CRC_EXTRA,
        )
    ), "a target alone must not publish"
    assert "detectedTargets" not in source._telemetry

    source._apply_lyrebird_status(
        _frame(
            AUTOSENSING_TARGET_ID,
            _target(7, 1, 2, b"VEHICLE", (0.5, 0.5, 0.6, 0.7)),
            AUTOSENSING_TARGET_CRC_EXTRA,
        )
    )
    assert source._apply_lyrebird_status(
        _frame(AUTOSENSING_STATUS_ID, _status(7, 2), AUTOSENSING_STATUS_CRC_EXTRA)
    )

    targets = source._telemetry["detectedTargets"]
    assert [t["type"] for t in targets] == ["PERSON", "VEHICLE"]
    assert targets[0]["confidence"] == pytest.approx(0.9)
    assert "confidence" not in targets[1], "NaN means the detector reported none, not zero"
    assert targets[0]["rect"] == [pytest.approx(v) for v in (0.1, 0.2, 0.3, 0.4)]
    assert source._telemetry["autoSensingActive"] is True
    assert source._telemetry["detectionSource"] == "edge"


def test_an_empty_cycle_clears_the_previous_targets():
    """The thing that clears a stale overlay: no target messages arrive, so only the status can."""
    source = MavlinkTelemetrySource(peer_host="")
    source._apply_lyrebird_status(
        _frame(
            AUTOSENSING_TARGET_ID,
            _target(1, 0, 1, b"PERSON", (0.1, 0.1, 0.2, 0.2)),
            AUTOSENSING_TARGET_CRC_EXTRA,
        )
    )
    source._apply_lyrebird_status(
        _frame(AUTOSENSING_STATUS_ID, _status(1, 1), AUTOSENSING_STATUS_CRC_EXTRA)
    )
    assert len(source._telemetry["detectedTargets"]) == 1

    source._apply_lyrebird_status(
        _frame(
            AUTOSENSING_STATUS_ID,
            _status(2, 0, active=0, source=b"none"),
            AUTOSENSING_STATUS_CRC_EXTRA,
        )
    )
    assert source._telemetry["detectedTargets"] == []
    assert source._telemetry["autoSensingActive"] is False


def test_targets_from_a_stale_cycle_are_discarded():
    """A dropped status leaves targets orphaned; they must not leak into the next cycle."""
    source = MavlinkTelemetrySource(peer_host="")
    source._apply_lyrebird_status(
        _frame(
            AUTOSENSING_TARGET_ID,
            _target(4, 0, 1, b"BOAT", (0, 0, 1, 1)),
            AUTOSENSING_TARGET_CRC_EXTRA,
        )
    )
    source._apply_lyrebird_status(
        _frame(
            AUTOSENSING_TARGET_ID,
            _target(5, 0, 1, b"ANIMAL", (0, 0, 1, 1)),
            AUTOSENSING_TARGET_CRC_EXTRA,
        )
    )
    source._apply_lyrebird_status(
        _frame(AUTOSENSING_STATUS_ID, _status(5, 1), AUTOSENSING_STATUS_CRC_EXTRA)
    )
    assert [t["type"] for t in source._telemetry["detectedTargets"]] == ["ANIMAL"]
