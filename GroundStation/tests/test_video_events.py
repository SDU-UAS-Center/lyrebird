import json

from video_events import (
    build_event_entry,
    format_sse_message,
    parse_discovery_response,
    serialize_ndjson_entry,
)


def test_build_event_entry_adds_timestamp_type_and_payload():
    entry = build_event_entry(
        "telemetry_sample",
        {"drone": "mini1", "packets": 3},
        timestamp_factory=lambda: "2026-05-29T10:00:00+00:00",
    )

    assert entry == {
        "ts": "2026-05-29T10:00:00+00:00",
        "type": "telemetry_sample",
        "drone": "mini1",
        "packets": 3,
    }


def test_serialize_ndjson_entry_uses_compact_single_line_json():
    line = serialize_ndjson_entry({"ts": "now", "type": "x", "nested": {"a": 1}})

    assert line == '{"ts":"now","type":"x","nested":{"a":1}}\n'
    assert json.loads(line) == {"ts": "now", "type": "x", "nested": {"a": 1}}


def test_format_sse_message_wraps_type_and_payload():
    message = format_sse_message("state", {"drones": []})

    assert message == b'data: {"type": "state", "payload": {"drones": []}}\n\n'


def test_parse_discovery_response_follows_shared_semantics_for_name_only_payload():
    # The aircraft always replies "LYREBIRD_HERE:<ip>:<name>", so a payload without an IP is
    # malformed; the shared parser treats the first field as the address and blanks the name.
    assert parse_discovery_response("LYREBIRD_HERE:mini1", "192.168.1.10") == {
        "ip": "mini1",
        "name": "UNKNOWN",
    }


def test_parse_discovery_response_accepts_ip_and_name_payload():
    assert parse_discovery_response("LYREBIRD_HERE:192.168.1.42:mini1", "192.168.1.10") == {
        "ip": "192.168.1.42",
        "name": "mini1",
    }


def test_parse_discovery_response_uses_remote_ip_for_missing_values():
    assert parse_discovery_response("LYREBIRD_HERE::", "192.168.1.10") == {
        "ip": "192.168.1.10",
        "name": "UNKNOWN",
    }


def test_parse_discovery_response_rejects_unrelated_messages():
    assert parse_discovery_response("OTHER:mini1", "192.168.1.10") is None
