from lyrebird_groundstation.dji_helpers import (
    DiscoveryResponse,
    build_command_url,
    candidate_subnet_ips,
    parse_discovery_response,
    parse_discovery_response_tuple,
    parse_telemetry_chunk,
    parse_telemetry_line,
)


def test_parse_discovery_response_accepts_ip_only_payload():
    assert parse_discovery_response(b"LYREBIRD_HERE:192.168.1.42") == DiscoveryResponse(
        ip_address="192.168.1.42",
        name="UNKNOWN",
    )


def test_parse_discovery_response_accepts_named_payload():
    assert parse_discovery_response("LYREBIRD_HERE:192.168.1.42:mini1") == DiscoveryResponse(
        ip_address="192.168.1.42",
        name="mini1",
    )


def test_parse_discovery_response_uses_fallback_ip_when_payload_ip_is_empty():
    assert parse_discovery_response(
        "LYREBIRD_HERE::mini1", fallback_ip="192.168.1.9"
    ) == DiscoveryResponse(
        ip_address="192.168.1.9",
        name="mini1",
    )


def test_parse_discovery_response_rejects_invalid_payloads():
    assert parse_discovery_response("OTHER:192.168.1.42") is None
    assert parse_discovery_response(b"\xff") is None
    assert parse_discovery_response("LYREBIRD_HERE:") is None


def test_parse_discovery_response_tuple_matches_legacy_ros_shape():
    assert parse_discovery_response_tuple(
        b"LYREBIRD_HERE::mini1",
        fallback_ip="192.168.1.9",
    ) == ("192.168.1.9", "mini1")


def test_parse_discovery_response_defaults_blank_name_to_unknown():
    assert parse_discovery_response("LYREBIRD_HERE:192.168.1.42:") == DiscoveryResponse(
        ip_address="192.168.1.42",
        name="UNKNOWN",
    )


def test_candidate_subnet_ips_skips_local_ip_and_invalid_addresses():
    assert candidate_subnet_ips(
        ["192.168.1.10", "not-an-ip"],
        host_octets=(9, 10, 11),
    ) == ["192.168.1.9", "192.168.1.11"]


def test_build_command_url_normalizes_slashes():
    assert build_command_url("http://192.168.1.42:8080", "/send/takeoff") == (
        "http://192.168.1.42:8080/send/takeoff"
    )
    assert build_command_url("http://192.168.1.42:8080/", "send/takeoff") == (
        "http://192.168.1.42:8080/send/takeoff"
    )


def test_parse_telemetry_line_adds_timestamp_to_json_object():
    assert parse_telemetry_line(
        '{"heading": 42.5, "speed": {"x": 1.0}}',
        timestamp="2026-05-29_12-00-00.000000",
    ) == {
        "heading": 42.5,
        "speed": {"x": 1.0},
        "timestamp": "2026-05-29_12-00-00.000000",
    }


def test_parse_telemetry_line_rejects_empty_invalid_and_non_object_lines():
    assert parse_telemetry_line("") is None
    assert parse_telemetry_line("not-json") is None
    assert parse_telemetry_line("[1, 2, 3]") is None


def test_parse_telemetry_chunk_keeps_partial_line_buffered():
    buffer, telemetry_items = parse_telemetry_chunk("", '{"heading": 1')

    assert buffer == '{"heading": 1'
    assert telemetry_items == []


def test_parse_telemetry_chunk_parses_complete_lines_with_timestamps():
    timestamps = iter(["t1", "t2"])

    buffer, telemetry_items = parse_telemetry_chunk(
        '{"heading": 1',
        '}\nnot-json\n{"batteryLevel": 88}\n{"partial": true',
        timestamp_factory=lambda: next(timestamps),
    )

    assert buffer == '{"partial": true'
    assert telemetry_items == [
        {"heading": 1, "timestamp": "t1"},
        {"batteryLevel": 88, "timestamp": "t2"},
    ]
